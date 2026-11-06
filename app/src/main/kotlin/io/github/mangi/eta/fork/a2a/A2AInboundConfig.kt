package io.github.mangi.eta.fork.a2a

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val LAN_BIND_ADDRESS = "0.0.0.0"

internal data class A2AInboundSnapshot(
    val enabled: Boolean,
    val port: Int,
    val lanAddress: String?,
    val advertisedEndpointUrl: String?,
    val token: String,
    val lastError: String?,
) {
    val agentCardUrl: String?
        get() = endpointUrl?.let(A2AInboundConfigStore::agentCardUrlForEndpoint)

    val endpointUrl: String?
        get() = advertisedEndpointUrl ?: lanAddress?.let { "http://$it:$port/a2a" }

    val bindAddress: String
        get() = if (advertisedEndpointUrl == null) LAN_BIND_ADDRESS else LOOPBACK_ADDRESS
}

/** Local settings and encrypted token for Eta's inbound A2A endpoint. */
internal class A2AInboundConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = A2ASecretStore(appContext)

    fun observe(): Flow<A2AInboundSnapshot> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(snapshot())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun snapshot(): A2AInboundSnapshot {
        ensureToken()
        return A2AInboundSnapshot(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            port = preferences.getInt(KEY_PORT, DEFAULT_PORT)
                .takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT,
            lanAddress = currentLanAddress(),
            advertisedEndpointUrl = preferences.getString(KEY_ADVERTISED_ENDPOINT_URL, null)
                ?.takeIf(::isValidAdvertisedEndpoint),
            token = loadToken().orEmpty(),
            lastError = preferences.getString(KEY_LAST_ERROR, null),
        )
    }

    fun setEnabled(enabled: Boolean) {
        ensureToken()
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_PORT, DEFAULT_PORT)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun setLastError(message: String?) {
        preferences.edit().apply {
            if (message.isNullOrBlank()) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, message.take(500))
        }.apply()
    }

    fun rotateToken(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        secrets.save(TOKEN_ALIAS, token)
        preferences.edit().remove(KEY_LAST_ERROR).apply()
        return token
    }

    fun loadToken(): String? = runCatching { secrets.load(TOKEN_ALIAS) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)

    /** Set by a verified transport adapter, never by an unauthenticated network request. */
    fun setAdvertisedEndpoint(url: String?) {
        require(url == null || isValidAdvertisedEndpoint(url)) { "Invalid advertised A2A endpoint" }
        preferences.edit().apply {
            if (url == null) remove(KEY_ADVERTISED_ENDPOINT_URL) else putString(KEY_ADVERTISED_ENDPOINT_URL, url)
        }.apply()
    }

    private fun ensureToken() {
        if (loadToken() == null) rotateToken()
    }

    companion object {
        const val DEFAULT_PORT = 43121
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        private const val PREFERENCES_NAME = "eta_a2a_inbound"
        private const val TOKEN_ALIAS = "a2a-inbound-v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_ADVERTISED_ENDPOINT_URL = "advertised_endpoint_url"

        internal fun isValidAdvertisedEndpoint(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.fragment == null &&
                uri.path == "/a2a"
        }.getOrDefault(false)

        internal fun agentCardUrlForEndpoint(endpointUrl: String): String? = runCatching {
            val uri = java.net.URI(endpointUrl)
            require(isValidAdvertisedEndpoint(endpointUrl))
            java.net.URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                "/.well-known/agent-card.json",
                null,
                null,
            ).toString()
        }.getOrNull()

        internal fun currentLanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address -> !address.isLoopbackAddress && address.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
