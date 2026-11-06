package io.github.mangi.eta.fork.a2a

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** A small HTTP/1.1 host for the A2A JSON-RPC binding. */
internal class A2AInboundEngine(
    private val port: Int,
    private val bearerTokenProvider: () -> String,
    private val tasks: A2AInboundTaskHandler,
    private val bindAddress: String = DEFAULT_BIND_ADDRESS,
    private val advertisedEndpointProvider: () -> String? = { null },
) {
    constructor(
        context: Context,
        port: Int,
        bearerToken: String,
    ) : this(
        port = port,
        bearerTokenProvider = { bearerToken },
        tasks = PersistentA2AInboundTaskHandler(
            dao = A2ADatabase.get(context.applicationContext).dao(),
            executor = AgentRuntimeA2AInboundExecutor(context.applicationContext),
        ),
    )

    constructor(
        context: Context,
        port: Int,
        bearerTokenProvider: () -> String,
        bindAddress: String = DEFAULT_BIND_ADDRESS,
        advertisedEndpointProvider: () -> String? = { null },
    ) : this(
        port = port,
        bearerTokenProvider = bearerTokenProvider,
        tasks = PersistentA2AInboundTaskHandler(
            dao = A2ADatabase.get(context.applicationContext).dao(),
            executor = AgentRuntimeA2AInboundExecutor(context.applicationContext),
        ),
        bindAddress = bindAddress,
        advertisedEndpointProvider = advertisedEndpointProvider,
    )

    /** Test and legacy-compatible constructor for a synchronous message-only endpoint. */
    constructor(
        port: Int,
        bearerTokenProvider: () -> String,
        complete: (prompt: String, history: List<AgentModelClient.ConversationMessage>) -> String,
    ) : this(
        port = port,
        bearerTokenProvider = bearerTokenProvider,
        tasks = LegacyA2AInboundMessageHandler(complete),
    )
    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newFixedThreadPool(MAX_CLIENTS) { runnable ->
        Thread(runnable, "eta-a2a-client").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "A2A inbound server is already running" }
        try {
            runBlocking { tasks.recoverPendingTasks() }
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, port), MAX_BACKLOG)
            }
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "eta-a2a-accept").apply {
                isDaemon = true
                start()
            }
        } catch (failure: Throwable) {
            running.set(false)
            throw failure
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
        tasks.close()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Throwable) {
                continue
            }
            runCatching { workers.execute { handle(client) } }
                .onFailure { client.closeQuietly() }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(connection.getInputStream())
            val requestLine = readAsciiLine(input, MAX_LINE_BYTES)
                ?: return@use writeResponse(connection, 400, errorResponse(null, -32600, "Missing request line"))
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size != 3) {
                return@use writeResponse(connection, 400, errorResponse(null, -32600, "Malformed request line"))
            }
            val method = requestParts[0].uppercase()
            val path = requestParts[1].substringBefore('?')
            val headers = readHeaders(input)
                ?: return@use writeResponse(connection, 431, errorResponse(null, -32600, "Headers too large"))
            val body = readBody(input, headers["content-length"]?.toIntOrNull())
                ?: return@use writeResponse(connection, 413, errorResponse(null, -32600, "Request body too large"))
            val origin = headers["origin"]

            if (method == "OPTIONS") {
                return@use writeResponse(connection, 204, "", contentType = null, origin = origin)
            }
            if (method == "GET" && path in AGENT_CARD_PATHS) {
                return@use writeResponse(connection, 200, agentCardJson(), origin = origin)
            }
            if (method != "POST" || path !in RPC_PATHS) {
                return@use writeResponse(connection, 404, errorResponse(null, -32601, "Not found"), origin = origin)
            }
            val requestedVersion = headers["a2a-version"]?.trim()
            if (requestedVersion != null && requestedVersion != A2A_PROTOCOL_VERSION) {
                return@use writeResponse(
                    connection,
                    400,
                    errorResponse(
                        null,
                        -32003,
                        "Unsupported A2A version: $requestedVersion; expected $A2A_PROTOCOL_VERSION",
                    ),
                    origin = origin,
                )
            }
            if (!isAuthorized(headers["authorization"])) {
                return@use writeResponse(
                    connection,
                    401,
                    errorResponse(null, -32001, "Bearer token required"),
                    origin = origin,
                    extraHeaders = mapOf("WWW-Authenticate" to "Bearer"),
                )
            }

            val rawBody = body.toString(Charsets.UTF_8)
            val requestId = extractRpcId(rawBody)
            val response = runCatching { handleRpc(rawBody) }.getOrElse { failure ->
                errorResponse(requestId, -32603, failure.message ?: "A2A request failed")
            }
            writeResponse(connection, 200, response, origin = origin)
        }
    }

    private fun handleRpc(raw: String): String {
        val request = runCatching { A2A_JSON.decodeFromString<A2AJsonRpcRequest>(raw) }
            .getOrElse { failure -> return errorResponse(null, -32700, failure.message ?: "Invalid JSON-RPC") }
        if (request.jsonrpc != "2.0") return errorResponse(request.id, -32600, "Invalid JSON-RPC version")
        return when (request.method) {
            "SendMessage" -> handleSendMessage(request)
            "GetTask" -> handleGetTask(request)
            "CancelTask" -> handleCancelTask(request)
            "SendStreamingMessage", "SubscribeToTask" ->
                errorResponse(request.id, -32601, "Streaming is not advertised by this Agent Card")
            else -> errorResponse(request.id, -32601, "Method not found: ${request.method}")
        }
    }

    private fun handleSendMessage(request: A2AJsonRpcRequest): String {
        val params = runCatching {
            A2A_JSON.decodeFromJsonElement<A2ASendMessageRequest>(request.params)
        }.getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Invalid params") }
        val response = runCatching { runBlocking { tasks.sendMessage(params) } }
            .getOrElse { failure -> return errorResponse(request.id, -32603, failure.message ?: "A2A request failed") }
        val result = A2A_JSON.encodeToJsonElement(response).toJsonObject()
        return A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result))
    }

    private fun handleGetTask(request: A2AJsonRpcRequest): String {
        if (!tasks.persistentTasks) return errorResponse(request.id, -32601, "GetTask is unavailable")
        val params = runCatching {
            A2A_JSON.decodeFromJsonElement<A2AGetTaskRequest>(request.params)
        }.getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Invalid params") }
        val task = runCatching { runBlocking { tasks.getTask(params.id, params.historyLength) } }
            .getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Unknown task") }
        val result = A2A_JSON.encodeToJsonElement(task).toJsonObject()
        return A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result))
    }

    private fun handleCancelTask(request: A2AJsonRpcRequest): String {
        if (!tasks.persistentTasks) return errorResponse(request.id, -32601, "CancelTask is unavailable")
        val params = runCatching {
            A2A_JSON.decodeFromJsonElement<A2ACancelTaskRequest>(request.params)
        }.getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Invalid params") }
        val task = runCatching { runBlocking { tasks.cancelTask(params.id) } }
            .getOrElse { failure -> return errorResponse(request.id, -32602, failure.message ?: "Unknown task") }
        val result = A2A_JSON.encodeToJsonElement(task).toJsonObject()
        return A2A_JSON.encodeToString(A2AJsonRpcResponse(id = request.id, result = result))
    }

    private fun agentCardJson(): String {
        val endpoint = advertisedEndpointProvider()
            ?.takeIf(::isValidHttpEndpoint)
            ?: A2AInboundConfigStore.currentLanAddress()?.let { "http://$it:$port/a2a" }
            ?: "http://127.0.0.1:$port/a2a"
        val authRequirement = buildJsonObject { put("bearerAuth", kotlinx.serialization.json.JsonArray(emptyList())) }
        val card = A2AAgentCard(
            name = "Eta on this device",
            description = "A private, token-protected A2A endpoint backed by Eta's Runtime with persistent tasks.",
            supportedInterfaces = listOf(
                A2AAgentInterface(
                    url = endpoint,
                    protocolBinding = "JSONRPC",
                    protocolVersion = A2A_PROTOCOL_VERSION,
                ),
            ),
            provider = A2AAgentProvider(url = "https://github.com/mangi-eta/Eta", organization = "Eta"),
            version = "1.0",
            capabilities = A2AAgentCapabilities(streaming = false, pushNotifications = false),
            securitySchemes = buildJsonObject {
                putJsonObject("bearerAuth") {
                    put("type", "http")
                    put("scheme", "bearer")
                }
            },
            securityRequirements = listOf(authRequirement),
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain"),
            skills = listOf(
                A2AAgentSkill(
                    id = "eta-model-chat",
                    name = "Eta model conversation",
                    description = "Submit a text task to Eta's Runtime, then poll GetTask and CancelTask as needed.",
                    tags = listOf("chat", "assistant", "tasks", "private-network"),
                    examples = listOf("Summarize this incident and list the next checks."),
                    inputModes = listOf("text/plain"),
                    outputModes = listOf("text/plain"),
                ),
            ),
        )
        return A2A_JSON.encodeToString(card)
    }

    private fun errorResponse(id: String?, code: Int, message: String): String =
        A2A_JSON.encodeToString(
            A2AJsonRpcResponse(
                id = id,
                error = A2AJsonRpcError(code = code, message = message),
            ),
        )

    private fun extractRpcId(raw: String): String? = runCatching {
        A2A_JSON.parseToJsonElement(raw).let { element ->
            (element as? JsonObject)?.get("id")?.toString()?.trim('"')
        }
    }.getOrNull()

    private fun isAuthorized(header: String?): Boolean {
        val expected = bearerTokenProvider().trim()
        if (expected.isEmpty()) return false
        val supplied = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')?.trim().orEmpty()
        return supplied.isNotEmpty() && java.security.MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun isValidHttpEndpoint(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readAsciiLine(input, MAX_LINE_BYTES) ?: return null
            total += line.length + 2
            if (total > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
    }

    private fun readBody(input: InputStream, contentLength: Int?): ByteArray? {
        val length = contentLength ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return body
    }

    private fun readAsciiLine(input: InputStream, limit: Int): String? {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() <= limit) {
            val current = input.read()
            if (current < 0) return null
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = output.toByteArray()
                return bytes.copyOf(bytes.size - 1).toString(Charsets.UTF_8)
            }
            output.write(current)
            previous = current
        }
        return null
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        body: String,
        contentType: String? = A2A_JSON_CONTENT_TYPE,
        origin: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            contentType?.let { append("Content-Type: $it\r\n") }
            append("A2A-Version: $A2A_PROTOCOL_VERSION\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    companion object {
        private const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        private const val MAX_CLIENTS = 4
        private const val MAX_BACKLOG = 32
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private val AGENT_CARD_PATHS = setOf("/.well-known/agent-card.json", "/.well-known/agent.json")
        private val RPC_PATHS = setOf("/", "/a2a")

        private fun kotlinx.serialization.json.JsonElement.toJsonObject(): JsonObject =
            this as JsonObject
    }
}

private fun Socket.closeQuietly() {
    runCatching(::close)
}
