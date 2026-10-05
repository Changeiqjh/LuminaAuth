package com.luminaauth.plugin

/**
 * 插件 YAML 的文本前置清洗与沙箱主机模型。
 *
 * 本文件只做纯文本处理与静态校验：不引用任何 Android API，也不暴露任何
 * 可供插件逃逸沙箱的能力，插件无法借道此处访问系统接口。
 */

/**
 * 插件 YAML 解析或校验失败。
 *
 * [message] 会原样展示给用户，因此必须写明"哪个键、第几项、为什么"。
 * 继承 [IllegalArgumentException]：既有调用方的异常捕获范围不变，不会漏接。
 */
class PluginParseException(message: String) : IllegalArgumentException(message)

/**
 * 沙箱白名单主机条目。YAML 中支持三种写法：
 *
 * ```
 * sandbox:
 *   hosts:
 *     - 218.6.130.195:1333                    # 字符串 + 端口：固定端口
 *     - portal.example.com                    # 字符串无端口：不限端口（历史行为，保持兼容）
 *     - { host: portal.example.com }          # 映射写法：端口缺省 443
 *     - { host: portal.example.com, port: 8443 }
 * ```
 *
 * [port] 的缺省值 443 在映射写法下生效；[anyPort] 只用于"字符串且未写端口"的历史写法。
 */
data class SandboxHost(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val anyPort: Boolean = false,
) {
    /** 交给运行期白名单（Plugin.hosts）的规范形式。 */
    val authority: String get() = if (anyPort) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 443
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        private val HOST_CHARS = Regex("^[A-Za-z0-9._-]+\$")
        private val MAP_KEYS = setOf("host", "port")

        /** 解析 hosts 数组的一项；[path] 形如 sandbox.hosts，[index] 为数组下标。 */
        fun parse(raw: Any?, path: String, index: Int): SandboxHost {
            val where = "$path[$index]"
            return when (raw) {
                null -> throw PluginParseException("$where 是空条目！请填写 host 或 host:port")
                is Map<*, *> -> parseMapEntry(raw, where)
                is List<*> -> throw PluginParseException(
                    "$where 不能是数组！请写 host 或 { host: example.com, port: 443 }"
                )
                is Boolean -> throw PluginParseException("$where 不能是布尔值！请填写 host 或 host:port")
                is Number -> throw PluginParseException(
                    "$where 是纯数字 $raw！主机名请写域名或 IP，端口要跟在冒号后面（host:port）"
                )
                else -> parseStringEntry(raw.toString(), where)
            }
        }

        private fun parseStringEntry(value: String, where: String): SandboxHost {
            val text = value.trim()
            if (text.isEmpty()) throw PluginParseException("$where 是空条目！请填写 host 或 host:port")
            if (text.contains("://")) {
                throw PluginParseException(
                    "$where 的值 \"$text\" 非法：不要带 http:// 或 https://，只写 host 或 host:port"
                )
            }
            if (text.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) {
                throw PluginParseException(
                    "$where 的值 \"$text\" 非法：不能包含空格、\"/\"、\"?\"、\"#\"、\"@\"，只写 host 或 host:port"
                )
            }
            val colon = text.indexOf(':')
            if (colon < 0) {
                checkHost(text, where)
                return SandboxHost(host = text.lowercase(), anyPort = true)
            }
            if (text.indexOf(':', colon + 1) >= 0) {
                throw PluginParseException("$where 的值 \"$text\" 非法：冒号过多，只允许 host:port 一个端口")
            }
            val hostPart = text.substring(0, colon)
            val portPart = text.substring(colon + 1)
            checkHost(hostPart, where)
            return SandboxHost(host = hostPart.lowercase(), port = parsePort(portPart, where, text))
        }

        private fun parseMapEntry(map: Map<*, *>, where: String): SandboxHost {
            map.keys.forEach { k ->
                val key = k?.toString().orEmpty()
                if (key !in MAP_KEYS) {
                    throw PluginParseException(
                        "$where 出现未知键 \"$key\"${keyHint(key, MAP_KEYS)}：映射写法只支持 { host: ..., port: ... }"
                    )
                }
            }
            val rawHost = map.entries.firstOrNull { it.key?.toString() == "host" }?.value
            val host = rawHost?.toString()?.trim().orEmpty()
            if (host.isEmpty()) {
                throw PluginParseException("$where 缺少 host！映射写法：{ host: example.com, port: 443 }")
            }
            checkHost(host, where)
            val rawPort = map.entries.firstOrNull { it.key?.toString() == "port" }?.value
            val port = if (rawPort == null) DEFAULT_PORT else parsePort(rawPort.toString().trim(), where, host)
            return SandboxHost(host = host.lowercase(), port = port)
        }

        private fun parsePort(raw: String, where: String, entry: String): Int {
            if (raw.isEmpty()) throw PluginParseException("$where 的值 \"$entry\" 端口为空！请写 host:port 或 host:443")
            val port = raw.toIntOrNull()
                ?: throw PluginParseException("$where 的值 \"$entry\" 端口非法：\"$raw\" 不是数字，端口范围 $MIN_PORT~$MAX_PORT")
            if (port < MIN_PORT || port > MAX_PORT) {
                throw PluginParseException("$where 的值 \"$entry\" 端口非法：$port 超出范围 $MIN_PORT~$MAX_PORT")
            }
            return port
        }

        private fun checkHost(host: String, where: String) {
            if (host.isBlank()) throw PluginParseException("$where 的主机名为空！请填写域名或 IP")
            if (host.length > 253 || !HOST_CHARS.matches(host)) {
                throw PluginParseException(
                    "$where 的主机名 \"$host\" 非法：只允许字母、数字、\"-\"、\".\"、\"_\""
                )
            }
        }
    }
}

/**
 * 插件 YAML 文本前置清洗：在送入解析器之前，自动清除从聊天工具、网页
 * 复制粘贴时混入的隐藏不可见字符。这类字符会让 hosts 之类的键悄悄变成
 * "hosts + 零宽字符"，解析结果看起来少了整段配置，报错却只指向别处。
 *
 * 处理范围：
 *  - 零宽系列：U+200B..U+200F、U+2060、U+FEFF、U+180E、U+00AD
 *  - 控制字符：U+0000..U+0008、U+000B、U+000C、U+000E..U+001F、U+007F
 *    （制表符、换行、回车保留，否则会破坏文档结构）
 *  - 全角空格 U+3000 转半角空格（YAML 不认全角缩进）
 *  - 行分隔符 U+2028 / U+2029 规范化为换行符
 *
 * 不做连续空格压缩：YAML 的层级由空格数量决定，压缩会破坏缩进结构。
 */
fun cleanHiddenChars(rawText: String): String {
    if (rawText.isEmpty()) return rawText
    return rawText
        .replace(ZERO_WIDTH_CHARS, "")
        .replace(CONTROL_CHARS, "")
        .replace(LINE_SEPARATORS, "\n")
        .replace(FULL_WIDTH_SPACE, ' ')
}

private val ZERO_WIDTH_CHARS = Regex("[\\u200B\\u200C\\u200D\\u200E\\u200F\\u2060\\uFEFF\\u180E\\u00AD]")
private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
private val LINE_SEPARATORS = Regex("[\\u2028\\u2029]")
private const val FULL_WIDTH_SPACE = '\u3000'

/** 给写错的键名一个"是否想写 xxx"的提示；距离太远则不猜。 */
internal fun keyHint(key: String, allowed: Collection<String>): String {
    if (key.isEmpty()) return ""
    val lower = key.lowercase()
    val best = allowed.minByOrNull { editDistance(lower, it) } ?: return ""
    return if (editDistance(lower, best) <= 2) "（是否想写 \"$best\"？）" else ""
}

/** 标准 Levenshtein 距离，只用于键名提示，输入长度都很短。 */
private fun editDistance(a: String, b: String): Int {
    val prev = IntArray(b.length + 1) { it }
    val cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        System.arraycopy(cur, 0, prev, 0, cur.size)
    }
    return prev[b.length]
}
