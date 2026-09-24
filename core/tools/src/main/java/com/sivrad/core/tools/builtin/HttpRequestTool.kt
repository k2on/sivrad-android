package com.sivrad.core.tools.builtin

import com.sivrad.core.tools.Confirmation
import com.sivrad.core.tools.ObjectSchema
import com.sivrad.core.tools.Preparation
import com.sivrad.core.tools.StringMapParam
import com.sivrad.core.tools.StringParam
import com.sivrad.core.tools.Tool
import com.sivrad.core.tools.ToolEnvironment
import com.sivrad.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * HTTP to a fixed set of base URLs the user configured (e.g. a Home Assistant
 * instance). Requires the device unlocked, like send_sms, since it can act on
 * the user's services. Requests that change state (anything but GET/HEAD)
 * additionally need an on-screen confirmation.
 */
class HttpRequestTool(
    private val client: OkHttpClient = defaultClient,
) : Tool {
    override val name = "http_request"
    override val description =
        "Make an HTTP request to one of the user's allowlisted services and get back the status and (truncated) response body."
    override val requiresUnlock = true
    override val parameters = ObjectSchema.of(
        "method" to StringParam("HTTP method.", enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
        "url" to StringParam("Absolute URL. Must start with one of the allowlisted base URLs.", maxLength = 2048),
        "headers" to StringMapParam("Optional request headers.", maxEntries = 20),
        "body" to StringParam("Optional request body (sent as-is; set Content-Type in headers).", maxLength = 8192),
        required = listOf("method", "url"),
    )

    override suspend fun prepare(args: JsonObject, env: ToolEnvironment): Preparation {
        val method = args.string("method")
        val url = args.string("url").toHttpUrlOrNull()
            ?: return Preparation.Rejected("\"${args.string("url")}\" is not a valid http(s) URL.")
        val allowlist = env.httpAllowlist
        if (allowlist.none { isAllowed(url, it) }) {
            return Preparation.Rejected(
                if (allowlist.isEmpty()) "No HTTP services are allowlisted; the user can add base URLs in the Sivrad setup screen."
                else "$url is not allowlisted. Allowed base URLs: ${allowlist.joinToString()}",
            )
        }
        val headers = try {
            Headers.Builder().apply { args.optStringMap("headers").forEach { (k, v) -> add(k, v) } }.build()
        } catch (e: IllegalArgumentException) {
            return Preparation.Rejected("Invalid header: ${e.message}")
        }
        val bodyText = args.optString("body")
        if (bodyText != null && method in listOf("GET", "HEAD")) {
            return Preparation.Rejected("$method requests cannot have a body.")
        }
        val body = when {
            method in listOf("GET", "HEAD") -> null
            else -> (bodyText ?: "").toRequestBody(headers["Content-Type"]?.toMediaTypeOrNull())
        }
        val request = Request.Builder().url(url).headers(headers).method(method, body).build()

        val confirmation = if (method in listOf("GET", "HEAD")) null else Confirmation(
            title = "Send HTTP request?",
            fields = listOfNotNull(
                "Request" to "$method $url",
                bodyText?.let { "Body" to it.take(500) },
            ),
            confirmLabel = "Send",
        )
        return Preparation.Ready(confirmation) { execute(request) }
    }

    private suspend fun execute(request: Request): ToolResult = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val source = resp.body?.source()
            val bytes = source?.let {
                it.request(MAX_BODY_BYTES + 1L)
                it.buffer.snapshot(minOf(it.buffer.size, MAX_BODY_BYTES + 1L).toInt()).toByteArray()
            } ?: ByteArray(0)
            val truncated = bytes.size > MAX_BODY_BYTES
            val text = String(bytes, 0, minOf(bytes.size, MAX_BODY_BYTES.toInt()), StandardCharsets.UTF_8)
            ToolResult.Success(
                "HTTP ${resp.code}${if (resp.message.isNotEmpty()) " ${resp.message}" else ""}\n" +
                    text + if (truncated) "\n[truncated]" else "",
            )
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 4_000L

        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false) // a redirect could leave the allowlist
                .build()
        }

        /**
         * [url] is allowed by [base] when scheme, host and port match exactly
         * and the path is the base path or below it at a segment boundary —
         * so `https://ha.lan/api` allows `/api/states` but not `/apiary`.
         */
        fun isAllowed(url: HttpUrl, base: String): Boolean {
            val b = base.trim().toHttpUrlOrNull() ?: return false
            if (url.scheme != b.scheme || url.host != b.host || url.port != b.port) return false
            val basePath = b.encodedPath.trimEnd('/')
            val path = url.encodedPath
            return basePath.isEmpty() || path == basePath || path.startsWith("$basePath/")
        }
    }
}
