package com.luminaauth.plugin

import org.json.JSONObject

/**
 * Pure evaluation rules for plugins: template expansion, response matching,
 * JSON path extraction and variable capture. No Android dependencies so the
 * whole rule set can be exercised by JVM unit tests.
 */
object PluginRules {

    private val TEMPLATE = Regex("""\$\{([^}]+)}""")

    fun expand(
        template: String,
        resolve: (String) -> String?,
        onMissing: (String) -> Unit = {},
    ): String = TEMPLATE.replace(template) { mr ->
        val key = mr.groupValues[1].trim()
        val value = resolve(key)
        if (value == null) {
            onMissing(key)
            ""
        } else {
            value
        }
    }

    fun matches(m: MatchDef, body: String): Boolean {
        m.bodyContains?.let { if (body.contains(it)) return true }
        m.bodyRegex?.let { if (Regex(it).containsMatchIn(body)) return true }
        if (m.jsonPath != null && m.jsonEquals != null) {
            val actual = jsonPath(body, m.jsonPath)
            if (actual != null && actual == m.jsonEquals) return true
        }
        return false
    }

    fun capture(c: CaptureDef, body: String): String? = when {
        c.jsonPath != null -> jsonPath(body, c.jsonPath)
        c.regex != null -> {
            val mr = Regex(c.regex).find(body)
            mr?.groupValues?.getOrNull(c.group)
        }
        else -> null
    }

    /** Extract a dotted JSON path from the first JSON object embedded in [body]. */
    fun jsonPath(body: String, path: String): String? {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            var current: Any? = JSONObject(body.substring(start, end + 1))
            path.split('.').forEach { seg ->
                current = when (val c = current) {
                    is JSONObject -> c.opt(seg)
                    else -> return null
                }
            }
            current?.toString()
        } catch (e: Exception) {
            null
        }
    }
}
