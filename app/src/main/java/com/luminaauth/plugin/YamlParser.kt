package com.luminaauth.plugin

/**
 * Minimal, safe YAML subset parser used exclusively for plugin files.
 *
 * Supported block-style subset:
 *  - nested maps ("key: value" / "key:")
 *  - block sequences ("- item" / "- key: value")
 *  - scalars: plain, "double quoted", 'single quoted', integers, booleans
 *  - comments starting with '#' (honouring quotes)
 * Unsupported YAML features (anchors, aliases, flow collections, tags,
 * multi-line scalars, tabs) are rejected or treated literally, which keeps
 * plugin files unambiguous and cheap to audit.
 *
 * 解析前先做 cleanHiddenChars 清洗，并拒绝重复键，避免条目被静默丢弃。
 *
 * Result types: Map<String,Any?>, List<Any?>, String, Long, Boolean, null.
 */
object YamlParser {

    private class Line(val indent: Int, val content: String, val number: Int)

    fun parse(text: String): Any? {
        // 前置清洗：调用方（PluginLoader）已清洗过一次，这里再兜一次，
        // 保证任何直接调用本类的代码都不会被隐藏字符带偏。
        val cleaned = cleanHiddenChars(text)
        val lines = ArrayList<Line>()
        cleaned.split('\n').forEachIndexed { index, raw ->
            val noComment = stripComment(raw)
            if (noComment.isBlank()) return@forEachIndexed
            if (noComment.contains('\t')) {
                throw IllegalArgumentException("第 ${index + 1} 行不允许使用 Tab 缩进，请改用空格：${raw.trim()}")
            }
            val indent = noComment.indexOfFirst { it != ' ' }
            lines.add(Line(indent, noComment.substring(indent).trimEnd(), index + 1))
        }
        if (lines.isEmpty()) return null
        val (value, _) = parseBlock(lines, 0, lines[0].indent)
        return value
    }

    /** Parse one map or sequence block at exactly [indent]; returns value and next index. */
    private fun parseBlock(lines: List<Line>, start: Int, indent: Int): Pair<Any?, Int> {
        if (start >= lines.size || lines[start].indent < indent) return null to start
        return if (lines[start].content == "-" || lines[start].content.startsWith("- ")) {
            parseSequence(lines, start, indent)
        } else {
            parseMap(lines, start, indent)
        }
    }

    private fun parseMap(lines: List<Line>, start: Int, indent: Int): Pair<Any?, Int> {
        val map = LinkedHashMap<String, Any?>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.indent < indent) break
            if (line.indent > indent) {
                throw IllegalArgumentException("第 ${line.number} 行缩进异常（本层应为 $indent 个空格）：${line.content}")
            }
            if (line.content == "-" || line.content.startsWith("- ")) {
                throw IllegalArgumentException("第 ${line.number} 行出现多余的序列项：${line.content}")
            }
            val colon = findKeyColon(line.content)
            if (colon < 0) {
                throw IllegalArgumentException("第 ${line.number} 行缺少键值冒号，应写成 key: value：${line.content}")
            }
            val key = unquote(line.content.substring(0, colon).trim())
            if (map.containsKey(key)) {
                throw IllegalArgumentException("第 ${line.number} 行出现重复的键 \"$key\"：同一个映射里每个键只能出现一次")
            }
            val rest = line.content.substring(colon + 1).trim()
            if (rest.isNotEmpty()) {
                map[key] = parseScalar(rest)
                i++
            } else {
                if (i + 1 < lines.size && lines[i + 1].indent > indent) {
                    val childIndent = lines[i + 1].indent
                    val (child, next) = parseBlock(lines, i + 1, childIndent)
                    map[key] = child
                    i = next
                } else {
                    map[key] = null
                    i++
                }
            }
        }
        return map to i
    }

    private fun parseSequence(lines: List<Line>, start: Int, indent: Int): Pair<Any?, Int> {
        val list = ArrayList<Any?>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.indent != indent) break
            if (!(line.content == "-" || line.content.startsWith("- "))) break
            val remainder = if (line.content == "-") "" else line.content.substring(2)
            when {
                remainder.isBlank() -> {
                    if (i + 1 < lines.size && lines[i + 1].indent > indent) {
                        val (child, next) = parseBlock(lines, i + 1, lines[i + 1].indent)
                        list.add(child)
                        i = next
                    } else {
                        list.add(null)
                        i++
                    }
                }
                findKeyColon(remainder) >= 0 -> {
                    // Inline first map key ("- id: x"); build a virtual map block
                    // whose first key sits in the column right after "- ".
                    val itemIndent = indent + 2
                    val itemLines = ArrayList<Line>()
                    itemLines.add(Line(itemIndent, remainder.trimEnd(), line.number))
                    var j = i + 1
                    while (j < lines.size && lines[j].indent > indent) {
                        itemLines.add(lines[j])
                        j++
                    }
                    val (child, consumed) = parseMap(itemLines, 0, itemIndent)
                    if (consumed < itemLines.size) {
                        throw IllegalArgumentException("第 ${line.number} 行的序列项格式不正确：${line.content}")
                    }
                    list.add(child)
                    i = j
                }
                else -> {
                    list.add(parseScalar(remainder.trim()))
                    i++
                }
            }
        }
        return list to i
    }

    // ---------------- scalars / helpers ----------------

    private fun parseScalar(raw: String): Any? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("\"") || s.startsWith("'")) return unquote(s)
        return when (s) {
            "true", "True", "TRUE" -> true
            "false", "False", "FALSE" -> false
            "null", "~", "Null" -> null
            else -> s.toLongOrNull() ?: s
        }
    }

    /** Remove surrounding quotes and resolve escapes. */
    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val sb = StringBuilder()
            var k = 1
            while (k < t.length - 1) {
                val c = t[k]
                if (c == '\\' && k + 1 < t.length - 1) {
                    when (val n = t[k + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        else -> sb.append(n)
                    }
                    k += 2
                } else {
                    sb.append(c)
                    k++
                }
            }
            return sb.toString()
        }
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length - 1).replace("''", "'")
        }
        return t
    }

    /** Find the ':' that separates a key, ignoring quoted regions. */
    private fun findKeyColon(s: String): Int {
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                // A mapping colon must be followed by a space or end of line;
                // "host:801" with no space is a plain scalar.
                ':' -> if (i == s.length - 1 || s[i + 1] == ' ') return i
            }
            i++
        }
        return -1
    }

    /** Strip a trailing comment while respecting quotes. */
    private fun stripComment(s: String): String {
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '#' -> return s.substring(0, i)
            }
            i++
        }
        return s
    }
}
