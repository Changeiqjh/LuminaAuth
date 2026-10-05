package com.luminaauth.plugin

/**
 * Per-run state for one plugin execution.
 * Holds user inputs, the selected ISP suffix, discovered runtime values and
 * variables captured from earlier responses (for multi-step flows).
 */
class SessionState(
    val plugin: Plugin,
    val inputs: MutableMap<String, String> = HashMap(),
    initialIspSuffix: String? = null,
) {
    var ispSuffix: String = initialIspSuffix
        ?: plugin.isps.firstOrNull { it.default }?.id
        ?: plugin.isps.firstOrNull()?.id
        ?: ""

    var ip: String? = null
    /** Optional base override applied to the first login request URL. */
    var loginUrlOverride: String? = null
    var rand: Int = (1000..9999).random()
    val vars: MutableMap<String, String> = HashMap()

    var outcome: Outcome = Outcome.NONE
    /** Machine-readable reason token, e.g. "already_online" / "http_denied". */
    var reason: String = ""
    /** Human-readable detail (server msg or error text). */
    var detail: String = ""

    /** Resolve a template key against inputs, captured vars and runtime values. */
    fun resolve(key: String): String? {
        return when (key) {
            "isp" -> ispSuffix
            "ip" -> ip
            "rand" -> rand.toString()
            else -> inputs[key] ?: vars[key]
        }
    }
}

enum class Outcome {
    NONE,
    ONLINE,
    OFFLINE,
    UNREACHABLE,
    SUCCESS,
    ALREADY_ONLINE,
    FAIL,
    ERROR,
}
