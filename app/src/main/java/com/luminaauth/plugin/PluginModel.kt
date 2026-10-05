package com.luminaauth.plugin

/**
 * Static data model for a YAML-defined auth plugin.
 * A plugin is pure data: there is no embedded code, so loading it cannot
 * execute anything. Runtime behaviour is fully determined by this model and
 * enforced by PluginRuntime / SandboxHttp.
 */
data class Plugin(
    val id: String,
    val name: String,
    val version: Int,
    val description: String,
    /** Substring patterns matched against the current SSID. Empty = any network. */
    val ssidPatterns: List<String>,
    /** Allow-list of host[:port] every plugin request must resolve to. */
    val hosts: List<String>,
    val fields: List<FieldDef>,
    val isps: List<IspDef>,
    val check: CheckDef?,
    val loginSteps: List<StepDef>,
    val source: PluginSource,
    val fileName: String,
) {
    companion object {
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
    }
}

enum class PluginSource { BUILTIN, EXTERNAL }

enum class FieldType { TEXT, PASSWORD }

data class FieldDef(
    val id: String,
    val label: String,
    val type: FieldType,
    val required: Boolean,
)

/**
 * ISP identity offered by the plugin. [id] is the account suffix spliced into
 * the request (e.g. "after"); [label] is shown in the dock bar; [default]
 * marks the entry pre-selected by the ISP segmented dock.
 */
data class IspDef(
    val id: String,
    val label: String,
    val default: Boolean = false,
) {
    /** [label] 的别名：分段 Dock 的 options 文本统一取这里。 */
    val name: String get() = label
}

data class CheckDef(
    val method: String,
    val url: String,
    val onlineContains: String?,
    val offlineContains: String?,
)

data class StepDef(
    val name: String,
    val method: String,
    val url: String,
    val expectStatus: Int?,
    val success: MatchDef?,
    val alreadyOnline: MatchDef?,
    val captures: List<CaptureDef>,
    val failMessageJsonPath: String?,
)

data class MatchDef(
    val bodyContains: String?,
    val bodyRegex: String?,
    val jsonPath: String?,
    val jsonEquals: String?,
)

data class CaptureDef(
    val name: String,
    val jsonPath: String?,
    val regex: String?,
    val group: Int,
)
