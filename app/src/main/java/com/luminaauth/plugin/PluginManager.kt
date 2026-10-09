package com.luminaauth.plugin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Discovers and manages plugins:
 *  - built-in plugins shipped under assets (*.plugin.yaml)
 *  - external plugins imported into app-specific external storage
 *    (getExternalFilesDir, no storage permission required)
 * Also persists the active plugin id.
 */
object PluginManager {

    private const val PLUGIN_DIR = "plugins"
    private const val PREFS = "plugin_prefs"
    private const val KEY_ACTIVE = "active_plugin_id"

    fun list(context: Context): List<Plugin> {
        val result = ArrayList<Plugin>()
        assetPlugins(context).forEach {
            if (result.none { x -> x.id == it.id }) result.add(it)
        }

        // External scanning is fully isolated so a failure can never discard
        // the built-in plugins already collected above.
        try {
            val dir = externalDir(context)
            dir.listFiles()?.filter { it.isFile && isPluginFileName(it.name) }?.sortedBy { it.name }
                ?.forEach { f ->
                    try {
                        val plugin = PluginLoader.load(
                            f.readText(Charsets.UTF_8), f.name, PluginSource.EXTERNAL,
                        )
                        if (result.none { it.id == plugin.id }) result.add(plugin)
                    } catch (e: Exception) {
                        com.luminaauth.LogBuffer.addDetail(
                            "PLUGIN", "load_fail file=${f.name} err=${e.message}",
                        )
                    }
                }
        } catch (e: Exception) {
            com.luminaauth.LogBuffer.addDetail(
                "PLUGIN", "external_scan_fail err=${e.message}",
            )
        }
        return result
    }

    /**
     * 绝不抛异常的取用当前插件：登录链路在后台线程调用它，抛出即可能演变成
     * 未捕获异常并终止进程。全部插件不可用时返回全空兜底对象，让上层以
     * “插件未配置”这类可读文案正常失败，而不是崩溃。
     */
    fun active(context: Context): Plugin {
        return try {
            val all = list(context)
            if (all.isEmpty()) {
                com.luminaauth.LogBuffer.addDetail("PLUGIN", "active_fallback reason=no_plugins")
                emptyPlugin()
            } else {
                val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_ACTIVE, null)
                all.firstOrNull { it.id == id }
                    ?: all.firstOrNull { it.source == PluginSource.BUILTIN }
                    ?: all.first()
            }
        } catch (t: Throwable) {
            com.luminaauth.LogBuffer.addDetail(
                "PLUGIN", "active_fail err=${t.javaClass.simpleName} ${t.message}",
            )
            emptyPlugin()
        }
    }

    /** 全空兜底插件：仅在插件资源全部不可用时返回，绝不为 null。 */
    private fun emptyPlugin(): Plugin = Plugin(
        id = "", name = "", version = 0, description = "",
        ssidPatterns = emptyList(), hosts = emptyList(), fields = emptyList(),
        isps = emptyList(), check = null, loginSteps = emptyList(),
        source = PluginSource.BUILTIN, fileName = "",
    )

    fun setActive(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, id).apply()
        com.luminaauth.LogBuffer.add("PLUGIN", "active id=$id")
    }

    /** Directory scanned for externally imported plugin documents. */
    fun pluginDir(context: Context): File = externalDir(context)

    /** Re-scan plugins; repair the active selection when it disappeared. */
    fun reload(context: Context) {
        val all = list(context)
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)
        if (all.isNotEmpty() && all.none { it.id == id }) {
            setActive(context, all.first().id)
        }
    }

    /** Default target SSIDs declared by the active plugin. */
    fun defaultSsids(context: Context): List<String> =
        runCatching { active(context).ssidPatterns }.getOrDefault(emptyList())

    /** Default login base URL (query stripped) declared by the active plugin. */
    fun defaultLoginUrl(context: Context): String =
        runCatching {
            active(context).loginSteps.firstOrNull()?.url?.substringBefore('?')
        }.getOrNull().orEmpty()

    /** Import a plugin document from a content [uri]; validates before saving. */
    fun import(context: Context, uri: Uri): Plugin {
        val rawName = queryDisplayName(context, uri) ?: "imported.plugin.yaml"
        val safeName = sanitizeFileName(rawName).let { n ->
            if (isPluginFileName(n)) n else "$n.plugin.yaml"
        }
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("cannot read selected file")

        val plugin = PluginLoader.load(text, safeName, PluginSource.EXTERNAL)
        val all = list(context)
        if (all.any { it.id == plugin.id }) {
            throw IllegalArgumentException("plugin id ${plugin.id} already exists")
        }
        val dir = externalDir(context)
        File(dir, safeName).writeText(text, Charsets.UTF_8)
        com.luminaauth.LogBuffer.add("PLUGIN", "import id=${plugin.id} file=$safeName")
        return plugin
    }

    /** Delete an external plugin file. Built-in plugins cannot be deleted. */
    fun delete(context: Context, plugin: Plugin) {
        if (plugin.source != PluginSource.EXTERNAL) return
        File(externalDir(context), plugin.fileName).delete()
        com.luminaauth.LogBuffer.add("PLUGIN", "delete id=${plugin.id}")
    }

    // ---------------- internals ----------------

    private const val FALLBACK_BUILTIN = "default_drcom.plugin.yaml"

    private fun assetPlugins(context: Context): List<Plugin> {
        val names = try {
            context.assets.list("")?.filter { isPluginFileName(it) }?.toMutableList()
                ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        // Defensive: if root-asset enumeration omits the known built-in, try to
        // open it directly by name.
        if (names.none { it.equals(FALLBACK_BUILTIN, ignoreCase = true) } &&
            assetExists(context, FALLBACK_BUILTIN)) {
            names.add(FALLBACK_BUILTIN)
        }
        com.luminaauth.LogBuffer.addDetail("PLUGIN", "builtin count=${names.size}")
        return names.mapNotNull { name ->
            try {
                val text = context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
                PluginLoader.load(text, name, PluginSource.BUILTIN)
            } catch (e: Exception) {
                com.luminaauth.LogBuffer.addDetail("PLUGIN", "asset_fail file=$name err=${e.message}")
                null
            }
        }
    }

    private fun assetExists(context: Context, name: String): Boolean = try {
        context.assets.open(name).use { true }
    } catch (e: Exception) {
        false
    }

    private fun externalDir(context: Context): File {
        val dir = context.getExternalFilesDir(PLUGIN_DIR)
        if (dir != null && !dir.exists()) dir.mkdirs()
        return dir ?: File(context.filesDir, PLUGIN_DIR).apply { if (!exists()) mkdirs() }
    }

    private fun isPluginFileName(name: String): Boolean =
        name.endsWith(".plugin.yaml") || name.endsWith(".plugin.yml")

    private fun sanitizeFileName(name: String): String {
        val base = name.substringBeforeLast('/').substringAfterLast('/')
        val cleaned = base.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        return cleaned.trim('.').ifEmpty { "imported.plugin.yaml" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }
}
