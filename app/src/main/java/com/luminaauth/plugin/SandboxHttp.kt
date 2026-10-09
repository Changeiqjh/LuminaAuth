package com.luminaauth.plugin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Sandboxed HTTP transport: the single network egress point for plugins.
 *
 * Guarantees:
 *  - only http/https requests
 *  - the resolved host[:port] must match the plugin's declared allow-list
 *  - redirects are never followed, so a request cannot be steered off-list
 *  - bounded connect/read timeouts
 * The response body is captured even for non-200 statuses so the runtime can
 * evaluate server messages.
 */
object SandboxHttp {

    data class Response(
        val status: Int,
        val body: String,
        val transportError: String?,
    ) {
        val ok: Boolean get() = transportError == null
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Mobile Safari/537.36"

    fun execute(
        plugin: Plugin,
        method: String,
        urlString: String,
        body: String? = null,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 8000,
    ): Response {
        val uri = try {
            URI(urlString)
        } catch (e: Exception) {
            return Response(-1, "", "malformed_url: ${e.message}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Response(-1, "", "scheme_denied: $scheme")
        }
        val host = uri.host?.lowercase()
            ?: return Response(-1, "", "no_host")
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        if (!isHostAllowed(plugin, host, uri.port)) {
            return Response(-1, "", "host_denied: $host:$port")
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (method == "POST" && body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val os: OutputStream = conn.outputStream
                os.write(body.toByteArray(Charsets.UTF_8))
                os.close()
            }
            val status = conn.responseCode
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            if (stream != null) {
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                reader.use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        sb.append(line)
                        // 上限防御：异常巨大的门户页面不应把内存打满
                        if (sb.length >= MAX_BODY_CHARS) break
                    }
                }
            }
            return Response(status, sb.toString(), null)
        } catch (e: Throwable) {
            // 兜底范围必须是 Throwable：Error（OOM / StackOverflow / 类初始化失败）
            // 若逃逸出登录线程就会成为未捕获异常并直接终止进程（点击登录后闪退）。
            return Response(-1, "", "transport: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 单次响应最多读取的字符数，防止异常巨大的页面耗尽内存。 */
    private const val MAX_BODY_CHARS = 262_144

    private fun isHostAllowed(plugin: Plugin, host: String, declaredPort: Int): Boolean {
        val effectivePort = if (declaredPort >= 0) declaredPort else null
        return plugin.hosts.any { entry ->
            val colon = entry.indexOf(':')
            if (colon < 0) {
                entry == host
            } else {
                val eh = entry.substring(0, colon)
                val ep = entry.substring(colon + 1).toIntOrNull() ?: return@any false
                eh == host && (effectivePort == null || ep == effectivePort)
            }
        }
    }
}
