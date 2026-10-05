package com.luminaauth.plugin

/**
 * 把 YAML 解析结果映射成经过校验的 [Plugin]。
 *
 * 校验原则：不静默丢弃任何条目。
 * 旧实现用 mapNotNull 过滤 hosts / isps / capture，键名写错、条目为空、
 * 类型不对都会被悄悄吃掉，最后统一报一句 requires at least one sandbox host，
 * 用户无法定位到底哪一行有问题。现在任何未知键、空条目、非法取值都会以
 * [PluginParseException] 中止加载，并在消息里写明"哪个键、第几项、为什么"。
 */
object PluginLoader {

    /** 只取 scheme + authority；path/query 可能含 ${...} 模板，不能当 URL 解析。 */
    private val URL_PREFIX = Regex("^(https?)://([^/?#]+)")

    private val ROOT_KEYS = setOf(
        "id", "name", "version", "description", "network",
        "sandbox", "sandbox_hosts", "fields", "isps", "check", "login",
    )
    private val NETWORK_KEYS = setOf("ssid")
    private val SANDBOX_KEYS = setOf("hosts")
    private val FIELD_KEYS = setOf("id", "name", "type", "required")
    private val ISP_KEYS = setOf("id", "suffix", "name", "label", "default")
    private val CHECK_KEYS = setOf("method", "url", "online_when", "offline_when")
    private val MATCH_KEYS = setOf("contains", "regex", "json", "json_path", "json_equals")
    private val JSON_KEYS = setOf("path", "equals")
    private val LOGIN_KEYS = setOf("steps")
    private val STEP_KEYS = setOf(
        "name", "method", "url", "expect_status",
        "success_when", "already_online_when", "capture", "fail",
    )
    private val CAPTURE_KEYS = setOf("name", "json_path", "regex", "group")
    private val FAIL_KEYS = setOf("json_path")
    private val METHODS = setOf("GET", "POST")

    fun load(text: String, fileName: String, source: PluginSource): Plugin {
        val cleaned = cleanHiddenChars(text)
        if (cleaned.isBlank()) {
            throw PluginParseException("插件内容为空：文件里没有任何可解析的配置")
        }

        val parsed = try {
            YamlParser.parse(cleaned)
        } catch (e: PluginParseException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw PluginParseException("YAML 语法错误：${e.message}")
        }

        val root = parsed as? Map<*, *>
            ?: throw PluginParseException("插件根节点必须是 key: value 映射，当前是${typeName(parsed)}")
        checkKeys(root, ROOT_KEYS, "顶层")

        val id = root.str("id", "顶层")
            ?: throw PluginParseException("缺少插件标识：顶层必须写 id: <唯一标识>")
        val name = root.str("name", "顶层") ?: id
        val version = root["version"]?.let { toInt(it, "顶层.version") } ?: 1
        val description = root.str("description", "顶层") ?: ""

        val network = root.section("network")
        val ssidPatterns = readSsid(network)

        val sandbox = root.section("sandbox")
        val hostsPath = if (sandbox?.containsKey("hosts") == true) "sandbox.hosts" else "sandbox_hosts"
        val hostEntries = readHosts(sandbox?.get("hosts") ?: root["sandbox_hosts"], hostsPath)
        val hosts = hostEntries.map { it.authority }

        val fields = readFields(root["fields"])
        val isps = readIsps(root["isps"])
        val check = readCheck(root.section("check"), hosts)

        val login = root.section("login")
            ?: throw PluginParseException("缺少 login 段：插件至少要写 login.steps 里的一个登录请求")
        checkKeys(login, LOGIN_KEYS, "login")
        val steps = readSteps(login["steps"], hosts)

        return Plugin(
            id = id,
            name = name,
            version = version,
            description = description,
            ssidPatterns = ssidPatterns,
            hosts = hosts,
            fields = fields,
            isps = isps,
            check = check,
            loginSteps = steps,
            source = source,
            fileName = fileName,
        )
    }

    // ---------------- 各段落读取 ----------------

    /**
     * 沙盒白名单主机。两种键名都接受：sandbox.hosts（内置插件写法）
     * 与顶层 sandbox_hosts（新写法）。解析交给 [SandboxHost]，条目非法即报错。
     */
    private fun readHosts(raw: Any?, path: String): List<SandboxHost> {
        if (raw == null) {
            throw PluginParseException(
                "缺少沙盒主机白名单：请写 sandbox.hosts 或 sandbox_hosts，至少一条（例如 - 218.6.130.195:1333）。" +
                    "插件只能访问白名单内的主机，这一项不能省。",
            )
        }
        val list = raw as? List<*> ?: throw PluginParseException(
            "$path 必须是数组（每行一个 - host），当前是${typeName(raw)}",
        )
        if (list.isEmpty()) {
            throw PluginParseException("$path 不能为空数组！请至少填写一条沙盒主机 host")
        }
        val entries = list.mapIndexed { index, item -> SandboxHost.parse(item, path, index) }
        val seen = HashSet<String>()
        entries.forEachIndexed { index, entry ->
            if (!seen.add(entry.authority)) {
                throw PluginParseException("$path[$index] 的 \"${entry.authority}\" 与前面的条目重复")
            }
        }
        return entries
    }

    private fun readSsid(network: Map<*, *>?): List<String> {
        if (network == null) return emptyList()
        checkKeys(network, NETWORK_KEYS, "network")
        val raw = network["ssid"] ?: return emptyList()
        val items: List<*> = when (raw) {
            is List<*> -> raw
            is Map<*, *> -> throw PluginParseException("network.ssid 必须是数组（- SSID），当前是键值映射")
            else -> raw.toString().split(',')
        }
        if (items.isEmpty()) {
            throw PluginParseException("network.ssid 不能为空：不需要限定 SSID 时请整段删掉 network")
        }
        return items.mapIndexed { index, item ->
            val ssid = item?.toString()?.trim().orEmpty()
            if (ssid.isEmpty()) {
                throw PluginParseException("network.ssid[$index] 是空条目！请填写 SSID 或删除该行")
            }
            ssid
        }
    }

    private fun readFields(raw: Any?): List<FieldDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw PluginParseException(
            "fields 必须是数组（- id: username / name: 用户名 / type: text），当前是${typeName(raw)}",
        )
        val seen = HashSet<String>()
        return list.mapIndexed { index, item ->
            val where = "fields[$index]"
            val m = item as? Map<*, *> ?: throw PluginParseException("$where 必须是键值映射，当前是${typeName(item)}")
            checkKeys(m, FIELD_KEYS, where)
            val fid = m.str("id", where)
                ?: throw PluginParseException("$where 缺少 id：密码框必须写成 - id: password 且 type: password")
            if (!seen.add(fid)) {
                throw PluginParseException("$where 的 id \"$fid\" 与前面的条目重复")
            }
            val typeRaw = (m.str("type", where) ?: "text").lowercase()
            val type = when (typeRaw) {
                "text" -> FieldType.TEXT
                "password" -> FieldType.PASSWORD
                else -> throw PluginParseException(
                    "$where（id=$fid）的 type \"$typeRaw\" 不支持：只能是 text 或 password。" +
                        "密码框写成 text 会把密码明文显示出来。",
                )
            }
            FieldDef(fid, m.str("name", where) ?: fid, type, readRequired(m, where, fid))
        }
    }

    private fun readRequired(m: Map<*, *>, where: String, fid: String): Boolean {
        if (!m.containsKey("required")) return true
        return when (val v = m["required"]) {
            null -> true
            is Boolean -> v
            else -> when (v.toString().trim().lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> throw PluginParseException(
                    "$where（id=$fid）的 required \"$v\" 不是布尔值：只能是 true 或 false",
                )
            }
        }
    }

    /** isps 条目可选 default: true —— 标记分段 Dock 的预选项。 */
    private fun readDefault(m: Map<*, *>, where: String): Boolean {
        if (!m.containsKey("default")) return false
        return when (val v = m["default"]) {
            null -> false
            is Boolean -> v
            else -> when (v.toString().trim().lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> throw PluginParseException(
                    "$where 的 default \"$v\" 不是布尔值：只能是 true 或 false",
                )
            }
        }
    }

    private fun readIsps(raw: Any?): List<IspDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw PluginParseException(
            "isps 必须是数组（- id: after / name: 教职工），当前是${typeName(raw)}",
        )
        if (list.isEmpty()) {
            throw PluginParseException("isps 不能为空数组：不需要 ISP 选择时请整段删掉 isps")
        }
        val seen = HashSet<String>()
        return list.mapIndexed { index, item ->
            val where = "isps[$index]"
            val m = item as? Map<*, *> ?: throw PluginParseException("$where 必须是键值映射，当前是${typeName(item)}")
            checkKeys(m, ISP_KEYS, where)
            val ispId = m.str("id", where) ?: m.str("suffix", where)
                ?: throw PluginParseException("$where 缺少 id：请写 isps: - id: after / name: 教职工")
            if (!seen.add(ispId)) {
                throw PluginParseException("$where 的 id \"$ispId\" 与前面的条目重复")
            }
            IspDef(
                ispId,
                m.str("name", where) ?: m.str("label", where) ?: ispId,
                readDefault(m, where),
            )
        }
    }

    private fun readCheck(section: Map<*, *>?, hosts: List<String>): CheckDef? {
        if (section == null) return null
        checkKeys(section, CHECK_KEYS, "check")
        val method = (section.str("method", "check") ?: "GET").uppercase()
        if (method !in METHODS) {
            throw PluginParseException("check.method 的值 \"$method\" 不支持：只能是 GET 或 POST")
        }
        val url = section.str("url", "check")
            ?: throw PluginParseException("check 缺少 url：请写 check: { url: \"http://<白名单主机>/...\" }")
        validateUrl(url, hosts, "check.url")
        return CheckDef(
            method,
            url,
            readSimpleMatch(section, "online_when"),
            readSimpleMatch(section, "offline_when"),
        )
    }

    /** check.online_when / offline_when 只支持 contains；其它写法以前会被静默忽略，现在直接报错。 */
    private fun readSimpleMatch(section: Map<*, *>, key: String): String? {
        if (!section.containsKey(key)) return null
        val raw = section[key]
            ?: throw PluginParseException("check.$key 是空的：请写 $key: { contains: \"...\" }")
        val map = raw as? Map<*, *> ?: throw PluginParseException(
            "check.$key 必须是映射，当前是${typeName(raw)}：\n请写成：\ncheck:\n  $key:\n    contains: \"...\"",
        )
        val unsupported = map.keys.map { it?.toString().orEmpty() }.filter { it != "contains" }
        if (unsupported.isNotEmpty()) {
            throw PluginParseException(
                "check.$key 只支持 contains，不支持 ${unsupported.joinToString(", ")}（判断在线状态请匹配响应里的固定文本）",
            )
        }
        return map.str("contains", "check.$key")
            ?: throw PluginParseException("check.$key.contains 是空值：请填写响应中出现的固定文本")
    }

    private fun readSteps(raw: Any?, hosts: List<String>): List<StepDef> {
        if (raw == null) {
            throw PluginParseException("缺少 login.steps：\n请写成：\nlogin:\n  steps:\n    - url: http://<白名单主机>/...")
        }
        val list = raw as? List<*> ?: throw PluginParseException(
            "login.steps 必须是数组（- name: ... / url: ...），当前是${typeName(raw)}",
        )
        if (list.isEmpty()) throw PluginParseException("login.steps 不能为空：至少要有一个登录请求")
        val seenNames = HashSet<String>()
        return list.mapIndexed { index, item ->
            val where = "login.steps[$index]"
            val m = item as? Map<*, *>
                ?: throw PluginParseException("$where 必须是键值映射（- name: pwd / url: ...），当前是${typeName(item)}")
            checkKeys(m, STEP_KEYS, where)
            val url = m.str("url", where)
                ?: throw PluginParseException("$where 缺少 url：每一步都要写完整请求地址")
            validateUrl(url, hosts, "$where.url")
            val method = (m.str("method", where) ?: "GET").uppercase()
            if (method !in METHODS) {
                throw PluginParseException("$where.method 的值 \"$method\" 不支持：只能是 GET 或 POST")
            }
            val stepName = m.str("name", where) ?: "step$index"
            if (!seenNames.add(stepName)) {
                throw PluginParseException("$where 的 name \"$stepName\" 与前面的步骤重复")
            }
            StepDef(
                name = stepName,
                method = method,
                url = url,
                expectStatus = m["expect_status"]?.let { toInt(it, "$where.expect_status") },
                success = m.section("success_when", "$where.success_when")?.let { readMatch(it, "$where.success_when") },
                alreadyOnline = m.section("already_online_when", "$where.already_online_when")
                    ?.let { readMatch(it, "$where.already_online_when") },
                captures = readCaptures(m["capture"], where),
                failMessageJsonPath = readFail(m, where),
            )
        }
    }

    private fun readMatch(map: Map<*, *>, where: String): MatchDef {
        checkKeys(map, MATCH_KEYS, where)
        if (map.isEmpty()) {
            throw PluginParseException("$where 是空的：请至少写一个条件（contains / regex / json）")
        }
        val json = map.section("json", "$where.json")
        if (json != null) checkKeys(json, JSON_KEYS, "$where.json")
        val regex = map.str("regex", where)
        if (regex != null) {
            try {
                Regex(regex)
            } catch (e: Exception) {
                throw PluginParseException("$where.regex 正则表达式非法：$regex（${e.message}）")
            }
        }
        val def = MatchDef(
            bodyContains = map.str("contains", where),
            bodyRegex = regex,
            jsonPath = json?.str("path", "$where.json") ?: map.str("json_path", where),
            jsonEquals = json?.str("equals", "$where.json") ?: map.str("json_equals", where),
        )
        if (def.bodyContains == null && def.bodyRegex == null && def.jsonPath == null && def.jsonEquals == null) {
            throw PluginParseException("$where 没有有效条件：contains / regex / json 至少要写一个非空值")
        }
        return def
    }

    private fun readCaptures(raw: Any?, where: String): List<CaptureDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw PluginParseException(
            "$where.capture 必须是数组（- name: ip / regex: ...），当前是${typeName(raw)}",
        )
        val seen = HashSet<String>()
        return list.mapIndexed { index, item ->
            val path = "$where.capture[$index]"
            val m = item as? Map<*, *>
                ?: throw PluginParseException("$path 必须是键值映射（- name: ip / regex: ...），当前是${typeName(item)}")
            checkKeys(m, CAPTURE_KEYS, path)
            val cname = m.str("name", path)
                ?: throw PluginParseException("$path 缺少 name：捕获到的变量要靠 name 传给后续步骤")
            if (!seen.add(cname)) {
                throw PluginParseException("$path 的 name \"$cname\" 与前面的捕获重复")
            }
            val regex = m.str("regex", path)
            if (regex != null) {
                try {
                    Regex(regex)
                } catch (e: Exception) {
                    throw PluginParseException("$path.regex 正则表达式非法：$regex（${e.message}）")
                }
            }
            val jsonPath = m.str("json_path", path)
            if (regex == null && jsonPath == null) {
                throw PluginParseException("$path 至少要写 regex 或 json_path 之一，否则捕获不到任何内容")
            }
            CaptureDef(cname, jsonPath, regex, m["group"]?.let { toInt(it, "$path.group") } ?: 1)
        }
    }

    private fun readFail(m: Map<*, *>, where: String): String? {
        if (!m.containsKey("fail")) return null
        val raw = m["fail"] ?: throw PluginParseException("$where.fail 是空的：请写 fail: { json_path: msg }")
        val map = raw as? Map<*, *> ?: throw PluginParseException(
            "$where.fail 必须是映射，当前是${typeName(raw)}：\n请写成：\nfail:\n  json_path: msg",
        )
        checkKeys(map, FAIL_KEYS, "$where.fail")
        return map.str("json_path", "$where.fail")
            ?: throw PluginParseException("$where.fail.json_path 是空值：请填写失败信息在响应里的字段名")
    }

    // ---------------- URL 白名单 ----------------

    /**
     * 声明的请求地址必须是绝对 http(s) 且命中白名单，只检查 scheme 与 host[:port]。
     * path/query 允许含 ${...} 模板与任意字符，因此不整体解析。
     */
    private fun validateUrl(url: String, hosts: List<String>, where: String) {
        val match = URL_PREFIX.find(url)
            ?: throw PluginParseException("$where 必须是 http(s) 绝对地址，当前是 \"$url\"")
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2].substringAfterLast('@')
        val colon = authority.lastIndexOf(':')
        val host: String
        val port: Int
        if (colon >= 0 && authority.substring(colon + 1).toIntOrNull() != null) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1).toInt()
        } else {
            host = authority
            port = if (scheme == "https") 443 else 80
        }
        val hostLc = host.lowercase()
        if (hostLc.isBlank()) throw PluginParseException("$where 里没有主机名：\"$url\"")
        val allowed = hosts.any { entry ->
            val ec = entry.indexOf(':')
            if (ec < 0) {
                entry == hostLc
            } else {
                entry.substring(0, ec) == hostLc &&
                    (entry.substring(ec + 1).toIntOrNull() ?: -1) == port
            }
        }
        if (!allowed) {
            throw PluginParseException(
                "$where 的主机 $hostLc:$port 不在沙盒白名单内。白名单：${hosts.joinToString(", ")}。" +
                    "如需访问，请在 sandbox.hosts 里补一条，端口要与 url 完全一致。",
            )
        }
    }

    // ---------------- 基础工具 ----------------

    /** 未知键一律报错：写错键名以前会被静默忽略，最后表现为"配置凭空少了"。 */
    private fun checkKeys(map: Map<*, *>, allowed: Set<String>, where: String) {
        map.keys.forEach { rawKey ->
            val key = rawKey?.toString().orEmpty()
            if (key.isEmpty()) throw PluginParseException("$where 出现空键：检查缩进与冒号写法")
            if (key !in allowed) {
                throw PluginParseException(
                    "$where 出现未知键 \"$key\"${keyHint(key, allowed)}。可用键：${allowed.joinToString(", ")}",
                )
            }
        }
    }

    private fun Map<*, *>.section(key: String, path: String = key): Map<*, *>? {
        if (!containsKey(key)) return null
        val v = this[key] ?: return null
        return v as? Map<*, *> ?: throw PluginParseException(
            "$path 必须是键值映射，当前是${typeName(v)}（检查缩进：子键要比 $key 多缩进）",
        )
    }

    private fun Map<*, *>.str(key: String, where: String): String? {
        if (!containsKey(key)) return null
        val v = this[key] ?: return null
        if (v is Map<*, *> || v is List<*>) {
            throw PluginParseException("$where.$key 应该是一段文本，当前是${typeName(v)}")
        }
        return v.toString().trim().ifEmpty { null }
    }

    private fun toInt(v: Any, where: String): Int = when (v) {
        is Number -> v.toInt()
        else -> v.toString().trim().toIntOrNull()
            ?: throw PluginParseException("$where 必须是整数，当前是 \"$v\"")
    }

    private fun typeName(v: Any?): String = when (v) {
        null -> "空值"
        is Map<*, *> -> "键值映射"
        is List<*> -> "数组"
        is Boolean -> "布尔值"
        is Number -> "数字"
        else -> "字符串"
    }
}
