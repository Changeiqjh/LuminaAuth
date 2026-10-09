package com.luminaauth.plugin

import android.content.Context
import com.luminaauth.LogBuffer
import com.luminaauth.LoginService

/**
 * Executes a plugin's check / login flow against a [SessionState].
 * All steps are data-driven; evaluation lives in [PluginRules] and every
 * request goes through [SandboxHttp]. Emits compact English machine logs.
 */
object PluginRuntime {

    /** Populate runtime values (local IP) before a run. */
    fun prepare(context: Context, state: SessionState) {
        if (state.ip == null) {
            state.ip = LoginService.getLocalIPv4(context)
        }
    }

    /** Custom auth address when set, otherwise the active plugin's login base. */
    fun getEffectiveLoginUrl(context: Context, customInputUrl: String): String =
        if (customInputUrl.isNotBlank()) customInputUrl
        else PluginManager.defaultLoginUrl(context)

    fun check(state: SessionState): Outcome {
        return try {
            val def = state.plugin.check
            if (def == null) {
                state.reason = "no_check"
                state.outcome = Outcome.ERROR
                return Outcome.ERROR
            }
            val url = PluginRules.expand(def.url, { state.resolve(it) }) { key ->
                LogBuffer.addDetail("PLUGIN", "template unresolved key=$key")
            }
            LogBuffer.addDetail("PLUGIN", "check step=begin method=${def.method} host=${hostOf(url)}")
            val resp = SandboxHttp.execute(state.plugin, def.method, url)
            if (!resp.ok) {
                state.reason = resp.transportError ?: "transport"
                state.detail = resp.transportError ?: ""
                state.outcome = Outcome.UNREACHABLE
                LogBuffer.addDetail("PLUGIN", "check result=unreachable reason=${state.reason}")
                return Outcome.UNREACHABLE
            }
            val outcome = when {
                def.onlineContains != null && resp.body.contains(def.onlineContains) -> Outcome.ONLINE
                def.offlineContains != null && resp.body.contains(def.offlineContains) -> Outcome.OFFLINE
                else -> Outcome.ERROR
            }
            state.outcome = outcome
            state.reason = outcome.name.lowercase()
            LogBuffer.addDetail(
                "PLUGIN",
                "check result=${outcome.name.lowercase()} http=${resp.status} len=${resp.body.length}",
            )
            outcome
        } catch (t: Throwable) {
            // 插件层兜底：沙箱内任何 Error / 异常都不许抛给宿主登录逻辑
            state.reason = "plugin_error"
            state.detail = "插件运行异常 " + t.javaClass.simpleName
            state.outcome = Outcome.ERROR
            LogBuffer.addDetail("PLUGIN", "check_fail " + android.util.Log.getStackTraceString(t))
            Outcome.ERROR
        }
    }

    fun login(state: SessionState): Outcome {
        return try {
            val plugin = state.plugin
            var stepIndex = 0
            plugin.loginSteps.forEach { step ->
                val expanded = PluginRules.expand(step.url, { state.resolve(it) }) { key ->
                    LogBuffer.addDetail("PLUGIN", "template unresolved key=$key")
                }
                val url = if (stepIndex == 0) applyBaseOverride(expanded, state.loginUrlOverride) else expanded
                stepIndex++
                LogBuffer.addDetail(
                    "PLUGIN",
                    "login step=${step.name} begin method=${step.method} host=${hostOf(url)}",
                )
                val resp = SandboxHttp.execute(plugin, step.method, url)
                if (!resp.ok) {
                    state.reason = resp.transportError ?: "transport"
                    state.detail = resp.transportError ?: ""
                    state.outcome = Outcome.ERROR
                    LogBuffer.addDetail("PLUGIN", "login step=${step.name} result=error reason=${state.reason}")
                    return Outcome.ERROR
                }
                step.captures.forEach { c ->
                    val value = PluginRules.capture(c, resp.body)
                    if (value != null) {
                        state.vars[c.name] = value
                        LogBuffer.addDetail("PLUGIN", "capture name=${c.name}")
                    }
                }

                if (step.expectStatus != null && resp.status != step.expectStatus) {
                    return failStep(state, step, resp, "http_status_${resp.status}")
                }
                if (step.success != null && PluginRules.matches(step.success, resp.body)) {
                    state.outcome = Outcome.SUCCESS
                    state.reason = "success"
                    LogBuffer.addDetail("PLUGIN", "login step=${step.name} result=success http=${resp.status}")
                    return Outcome.SUCCESS
                }
                if (step.alreadyOnline != null && PluginRules.matches(step.alreadyOnline, resp.body)) {
                    state.outcome = Outcome.ALREADY_ONLINE
                    state.reason = "already_online"
                    LogBuffer.addDetail("PLUGIN", "login step=${step.name} result=already_online")
                    return Outcome.ALREADY_ONLINE
                }
                if (step.success != null) {
                    return failStep(state, step, resp, "match_failed")
                }
                // No explicit success rule: accept 2xx/3xx and continue to next step.
                if (resp.status !in 200..399) {
                    return failStep(state, step, resp, "http_status_${resp.status}")
                }
            }
            state.outcome = Outcome.SUCCESS
            state.reason = "success"
            LogBuffer.addDetail("PLUGIN", "login result=success steps=${plugin.loginSteps.size}")
            Outcome.SUCCESS
        } catch (t: Throwable) {
            // 插件层兜底：沙箱内任何 Error / 异常都不许抛给宿主登录逻辑
            state.reason = "plugin_error"
            state.detail = "插件运行异常 " + t.javaClass.simpleName
            state.outcome = Outcome.ERROR
            LogBuffer.addDetail("PLUGIN", "login_fail " + android.util.Log.getStackTraceString(t))
            Outcome.ERROR
        }
    }

    private fun failStep(
        state: SessionState,
        step: StepDef,
        resp: SandboxHttp.Response,
        reason: String,
    ): Outcome {
        val serverMsg = step.failMessageJsonPath?.let { PluginRules.jsonPath(resp.body, it) }
        state.reason = reason
        state.detail = serverMsg?.takeIf { it.isNotBlank() } ?: snippet(resp.body)
        state.outcome = Outcome.FAIL
        LogBuffer.addDetail(
            "PLUGIN",
            "login step=${step.name} result=fail reason=$reason msg=${state.detail}",
        )
        return Outcome.FAIL
    }

    private fun applyBaseOverride(url: String, base: String?): String {
        if (base.isNullOrBlank() || base.contains("\${")) return url
        val clean = base.substringBefore('?').substringBefore('#').trim()
        if (clean.isBlank()) return url
        val query = url.substringAfter('?', "")
        return if (query.isEmpty()) clean else clean + "?" + query
    }

    private fun hostOf(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "${uri.host}$port"
        } catch (e: Exception) {
            "na"
        }
    }

    private fun snippet(body: String): String {
        val b = body.trim()
        return if (b.length <= 80) b else b.substring(0, 80)
    }
}
