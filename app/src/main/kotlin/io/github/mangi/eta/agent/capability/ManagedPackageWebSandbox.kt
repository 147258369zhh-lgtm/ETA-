package io.github.mangi.eta.agent.capability

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.tool.AgentLocalTools
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
internal class ManagedPackageWebSandbox(
    private val snapshot: ManagedToolPackageRuntimeSnapshot,
    private val localTools: AgentLocalTools,
) {
    fun execute(
        binding: ManagedPackageToolBinding,
        source: String,
        arguments: JSONObject,
    ): AgentModelClient.ToolResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure("PACKAGE_RUNTIME_THREAD", "插件执行不能在主线程启动")
        }
        val handler = Handler(Looper.getMainLooper())
        val finished = CountDownLatch(1)
        val settled = AtomicBoolean(false)
        var webView: WebView? = null
        val bridge = HostBridge(snapshot, localTools, binding) { payload ->
            if (settled.compareAndSet(false, true)) {
                payloadRef = payload
                finished.countDown()
            }
        }
        handler.post {
            runCatching {
                val view = WebView(snapshot.context)
                webView = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowContentAccess = false
                    allowFileAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    blockNetworkLoads = true
                    databaseEnabled = false
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = true
                }
                view.webChromeClient = android.webkit.WebChromeClient()
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun onPageFinished(view: WebView, url: String) {
                        val invocation = JSONObject.quote(binding.tool.name)
                        val argumentJson = JSONObject.quote(arguments.toString())
                        view.evaluateJavascript(
                            """
                            (async function() {
                              try {
                                const name = $invocation;
                                const params = JSON.parse($argumentJson);
                                const exported = window.exports && window.exports[name];
                                if (typeof exported !== 'function') {
                                  throw new Error('exported tool is missing: ' + name);
                                }
                                const value = await exported(params);
                                EtaPackageHost.finish(JSON.stringify({ok: true, value: value === undefined ? null : value}));
                              } catch (error) {
                                EtaPackageHost.finish(JSON.stringify({ok: false, error: String(error && (error.stack || error.message) || error)}));
                              }
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                view.addJavascriptInterface(bridge, "EtaPackageHost")
                view.loadDataWithBaseURL("https://eta.invalid/plugin/", html(source), "text/html", "UTF-8", null)
            }.onFailure { error ->
                if (settled.compareAndSet(false, true)) {
                    payloadRef = JSONObject().put("ok", false).put("error", error.message ?: "WebView 创建失败").toString()
                    finished.countDown()
                }
            }
        }
        val completed = finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handler.post {
            runCatching {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.removeJavascriptInterface("EtaPackageHost")
                webView?.destroy()
            }
        }
        if (!completed) {
            return failure("PACKAGE_TIMEOUT", "插件执行超过 ${TIMEOUT_SECONDS} 秒，已终止本次调用")
        }
        val raw = payloadRef ?: return failure("PACKAGE_EMPTY_RESULT", "插件没有返回结果")
        val value = runCatching { JSONObject(raw) }.getOrElse {
            return failure("PACKAGE_INVALID_RESULT", "插件返回了无法解析的结果")
        }
        if (!value.optBoolean("ok", false)) {
            return AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("error", JSONObject()
                        .put("code", "PACKAGE_EXECUTION_FAILED")
                        .put("message", value.optString("error").take(MAX_ERROR_CHARS)))
                    .toString(),
                sensitive = true,
                command = binding.tool.name,
            )
        }
        val result = value.opt("value")
        val content = when (result) {
            null, JSONObject.NULL -> "null"
            is String -> if (result.startsWith("{") || result.startsWith("[")) result else result
            else -> result.toString()
        }
        return AgentModelClient.ToolResult(
            content = content.take(MAX_RESULT_CHARS),
            sensitive = true,
            command = binding.tool.name,
        )
    }

    @Volatile
    private var payloadRef: String? = null

    private fun html(source: String): String {
        val safeSource = source.replace("</script", "<\\/script", ignoreCase = true)
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <script>
        window.exports = Object.create(null);
        window.module = {exports: window.exports};
        window.console = Object.freeze({log:function(){},info:function(){},warn:function(){},error:function(){},debug:function(){}});
        window.OPERIT_CLEAN_ON_EXIT_DIR = '/storage/emulated/0/Android/data/fuck.andes/files/plugin-temp';
        function etaNode(path) {
          return new Proxy(function(){}, {
            get: function(target, property) {
              if (property === 'then' || property === Symbol.toStringTag) return undefined;
              return etaNode(path.concat(String(property)));
            },
            apply: function(target, thisArg, args) {
              let params = {};
              if (args.length === 1 && args[0] !== null && typeof args[0] === 'object' && !Array.isArray(args[0])) {
                params = args[0];
              } else {
                args.forEach((value, index) => { params[String(index)] = value; });
              }
              try {
                const raw = EtaPackageHost.call(path.join('.'), JSON.stringify(params === undefined ? {} : params));
                const response = JSON.parse(raw);
                if (response && response.hostError) throw new Error(response.hostError);
                return Promise.resolve(response);
              } catch (error) {
                return Promise.reject(error);
              }
            }
          });
        }
        window.Tools = etaNode([]);
        window.toolCall = async function(request) {
          if (!request || typeof request.name !== 'string') throw new Error('toolCall.name is required');
          const raw = EtaPackageHost.call('@toolCall.' + request.name, JSON.stringify(request.params || {}));
          const response = JSON.parse(raw);
          if (response && response.hostError) throw new Error(response.hostError);
          return response;
        };
        window.ToolPkg = Object.freeze({registerTool:function(){},registerToolPkg:function(){}});
        </script><script>$safeSource</script></head><body></body></html>"""
    }

    private class HostBridge(
        private val snapshot: ManagedToolPackageRuntimeSnapshot,
        private val localTools: AgentLocalTools,
        private val binding: ManagedPackageToolBinding,
        private val finishHandler: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun finish(payload: String) {
            finishHandler(payload.take(MAX_TRANSPORT_CHARS))
        }

        @JavascriptInterface
        fun call(path: String, paramsJson: String): String {
            val params = runCatching { JSONObject(paramsJson.ifBlank { "{}" }) }.getOrElse {
                return JSONObject().put("hostError", "插件参数不是 JSON object").toString()
            }
            val call = mapCall(path, params) ?: return JSONObject()
                .put("hostError", "宿主未开放该 Operit 能力：${path.take(80)}")
                .toString()
            val requiredPermission = permissionFor(call.first)
            if (requiredPermission != null && binding.tool.permissions.isNotEmpty() &&
                requiredPermission !in binding.tool.permissions
            ) {
                return JSONObject()
                    .put("hostError", "包未声明 $requiredPermission 能力，已阻止 ${path.take(80)}")
                    .toString()
            }
            val localCall = AgentModelClient.ToolCall(
                id = "plugin-${UUID.randomUUID()}",
                name = call.first,
                argumentsJson = call.second.toString(),
            )
            val result = runCatching { localTools.execute(localCall) }.getOrElse { error ->
                return JSONObject().put("hostError", error.message ?: "宿主工具执行失败").toString()
            }
            return adaptResult(path, result.content)
        }

        private fun mapCall(path: String, p: JSONObject): Pair<String, JSONObject>? {
            val compact = path.removePrefix("@toolCall.")
            if (path.startsWith("@toolCall.")) return topLevel(compact, p)
            return when {
                path == "Files.read" -> pair("read_file", JSONObject().put("path", p.text("path", 0)))
                path == "Files.write" -> pair("write_file", JSONObject()
                    .put("path", p.text("path", 0))
                    .put("content", p.text("content", 1, allowEmpty = true))
                    .put("append", p.boolean("append", 2, false)))
                path == "Files.apply" -> pair("edit_file", JSONObject()
                    .put("path", p.text("path", 0))
                    .put("old_text", p.text("oldText", 1, fallbackKey = "old_text", allowEmpty = true))
                    .put("new_text", p.text("newText", 2, fallbackKey = "new_text", allowEmpty = true)))
                path == "Files.exists" || path == "Files.info" ->
                    pair("stat_file", JSONObject().put("path", p.text("path", 0)))
                path == "Files.list" -> pair("list_directory", JSONObject()
                    .put("path", p.text("path", 0))
                    .put("show_hidden", p.boolean("showHidden", 1, false)))
                path == "Files.mkdir" -> terminal("mkdir -p -- ${shellQuote(p.text("path", 0))}")
                path == "Files.deleteFile" -> terminal(
                    if (p.boolean("recursive", 1, false)) "rm -rf -- ${shellQuote(p.text("path", 0))}"
                    else "rm -f -- ${shellQuote(p.text("path", 0))}",
                )
                path == "Files.copy" -> terminal(
                    "cp ${if (p.boolean("recursive", 2, false)) "-R " else ""}-- " +
                        "${shellQuote(p.text("source", 0))} ${shellQuote(p.text("destination", 1))}",
                )
                path == "Files.download" -> pair("fetch_url", JSONObject().put("url", p.text("url", 0)))
                path == "System.sleep" -> pair("wait", JSONObject().put("duration_ms", p.long("milliseconds", 0, 1_000L)))
                path == "System.shell" || path == "System.terminal.exec" -> terminal(p.text("command", 0))
                path == "System.getDeviceInfo" -> pair("get_current_context", JSONObject())
                path == "System.startApp" -> pair("launch_app", JSONObject().put("package_name", p.text("packageName", 0)))
                path == "System.listApps" -> pair("search_apps", JSONObject().put("query", p.text("query", 0, allowEmpty = true)))
                path == "System.getNotifications" -> pair("recent_notifications", JSONObject()
                    .put("limit", p.integer("limit", 0, 10)))
                path == "UI.tap" -> pair("tap", JSONObject().put("x", p.integer("x", 0, 0)).put("y", p.integer("y", 1, 0)))
                path == "UI.swipe" -> pair("swipe", JSONObject()
                    .put("x1", p.integer("startX", 0, 0)).put("y1", p.integer("startY", 1, 0))
                    .put("x2", p.integer("endX", 2, 0)).put("y2", p.integer("endY", 3, 0))
                    .put("duration_ms", p.integer("duration", 4, 300)))
                path == "UI.pressKey" -> pair("press_key", JSONObject().put("button", p.text("key", 0)))
                path == "UI.setText" -> pair("type_text", JSONObject().put("text", p.text("text", 0, allowEmpty = true)))
                path == "Net.visit" -> pair("fetch_url", JSONObject().put("url", p.text("url", 0)))
                path == "Memory.get" -> pair("memory_get", JSONObject().put("query", p.text("query", 0, allowEmpty = true)))
                path == "Memory.write" -> pair("memory_write", JSONObject()
                    .put("content", p.text("content", 0, allowEmpty = true))
                    .put("scope", p.text("scope", 1, allowEmpty = true).ifBlank { "global" }))
                else -> null
            }
        }

        private fun topLevel(name: String, p: JSONObject): Pair<String, JSONObject>? = when {
            name == "http_request" -> if (p.optString("method", "GET").equals("GET", true)) {
                pair("fetch_url", JSONObject().put("url", p.text("url", 0)))
            } else null
            name == "get_device_info" -> pair("get_current_context", JSONObject())
            name == "start_app" -> pair("launch_app", JSONObject().put("package_name", p.text("package_name", 0)))
            name == "list_installed_apps" -> pair("search_apps", JSONObject().put("query", ""))
            name == "get_notifications" -> pair("recent_notifications", JSONObject().put("limit", p.optInt("limit", 10)))
            name == "ffmpeg_execute" -> terminal(p.text("command", 0))
            else -> null
        }

        private fun pair(name: String, arguments: JSONObject): Pair<String, JSONObject> = name to arguments

        private fun terminal(command: String): Pair<String, JSONObject> = pair("terminal", JSONObject()
            .put("command", command)
            .put("environment", "android")
            .put("timeout_ms", 60_000))

        private fun adaptResult(path: String, raw: String): String {
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            if (parsed != null && (parsed.optBoolean("error", false) || parsed.has("error_code"))) {
                return JSONObject().put(
                    "hostError",
                    parsed.optString("message", parsed.optString("error", "宿主工具返回错误")),
                ).toString()
            }
            val value: Any = when {
                path in setOf("Files.read", "Net.visit") ->
                    parsed?.optString("content")?.takeIf(String::isNotEmpty) ?: parsed?.optString("text") ?: raw
                path in setOf("Files.exists") -> parsed?.optBoolean("exists", true) ?: true
                path in setOf("Files.write", "Files.mkdir", "Files.deleteFile", "Files.copy") -> true
                path.startsWith("System.shell") || path == "System.terminal.exec" -> parsed ?: raw
                else -> parsed ?: raw
            }
            return JSONObject().put("value", value).toString()
        }

        private fun JSONObject.text(
            key: String,
            position: Int,
            fallbackKey: String? = null,
            allowEmpty: Boolean = false,
        ): String {
            val value = when {
                has(key) -> optString(key)
                fallbackKey != null && has(fallbackKey) -> optString(fallbackKey)
                has(position.toString()) -> optString(position.toString())
                else -> ""
            }
            if (!allowEmpty && value.isBlank()) throw IllegalArgumentException("缺少参数 $key")
            return value
        }

        private fun JSONObject.boolean(key: String, position: Int, default: Boolean): Boolean =
            when {
                has(key) -> optBoolean(key, default)
                has(position.toString()) -> optBoolean(position.toString(), default)
                else -> default
            }

        private fun JSONObject.integer(key: String, position: Int, default: Int): Int =
            when {
                has(key) -> optInt(key, default)
                has(position.toString()) -> optInt(position.toString(), default)
                else -> default
            }

        private fun JSONObject.long(key: String, position: Int, default: Long): Long =
            when {
                has(key) -> optLong(key, default)
                has(position.toString()) -> optLong(position.toString(), default)
                else -> default
            }

        private fun permissionFor(toolName: String): String? = when (toolName) {
            in AgentTerminalToolCatalog.names -> "terminal"
            "read_file", "write_file", "edit_file", "stat_file", "list_directory", "glob_files", "grep_files" -> "files"
            "fetch_url", "web_search", "browser_use" -> "network"
            "memory_get", "memory_write" -> "memory"
            "automation_manage", "schedule_create" -> "automation"
            "get_current_context", "search_apps", "launch_app", "recent_notifications" -> "system"
            "tap", "swipe", "press_key", "type_text", "observe_screen" -> "ui"
            else -> null
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val MAX_RESULT_CHARS = 64 * 1024
        const val MAX_TRANSPORT_CHARS = 128 * 1024
        const val MAX_ERROR_CHARS = 4_000

        fun failure(code: String, message: String): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("error", JSONObject().put("code", code).put("message", message))
                .toString(),
            sensitive = true,
        )
    }
}
