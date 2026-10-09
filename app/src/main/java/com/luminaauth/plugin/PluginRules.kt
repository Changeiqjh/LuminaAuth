package com.luminaauth.plugin

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Pure evaluation rules for plugins: template expansion, response matching,
 * JSON path extraction and variable capture. No Android dependencies so the
 * whole rule set can be exercised by JVM unit tests.
 *
 * 不用 kotlin.text.Regex：真机上本类曾以 ExceptionInInitializerError /
 * NoClassDefFoundError 失败（插件层适配缺陷），因此模板展开改为纯字符扫描、
 * 正则改用 JDK 的 [Pattern] 现用现编译，类初始化不再有可失败的静态字段。
 */
object PluginRules {

    fun expand(
        template: String,
        resolve: (String) -> String?,
        onMissing: (String) -> Unit = {},
    ): String {
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '$' && i + 1 < template.length && template[i + 1] == '{') {
                val end = template.indexOf('}', i + 2)
                if (end > i + 2) {
                    val key = template.substring(i + 2, end).trim()
                    val value = resolve(key)
                    if (value == null) {
                        onMissing(key)
                    } else {
                        out.append(value)
                    }
                    i = end + 1
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    fun matches(m: MatchDef, body: String): Boolean {
        m.bodyContains?.let { if (body.contains(it)) return true }
        m.bodyRegex?.let { pattern ->
            // 非法正则不能让登录链路抛异常：按“不匹配”处理
            try {
                if (Pattern.compile(pattern).matcher(body).find()) return true
            } catch (ignored: Throwable) {
            }
        }
        if (m.jsonPath != null && m.jsonEquals != null) {
            val actual = jsonPath(body, m.jsonPath)
            if (actual != null && actual == m.jsonEquals) return true
        }
        return false
    }

    fun capture(c: CaptureDef, body: String): String? = when {
        c.jsonPath != null -> jsonPath(body, c.jsonPath)
        c.regex != null -> {
            // 非法正则 / 越界捕获组都退化为“无捕获”，不向上抛异常
            try {
                val matcher = Pattern.compile(c.regex).matcher(body)
                if (matcher.find() && c.group in 0..matcher.groupCount()) matcher.group(c.group) else null
            } catch (ignored: Throwable) {
                null
            }
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
