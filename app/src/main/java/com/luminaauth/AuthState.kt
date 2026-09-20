package com.luminaauth

/**
 * 认证状态机（全局单例，线程安全）：
 * - 用于区分"是否需要真正发起认证"与"已认证成功仅做轻量探测"，
 *   杜绝重复认证 / 重复查询。
 *
 * 状态流转：
 *   NO_WIFI ──(连上校园WiFi)──> NEED_AUTH ──(登录成功/已在线)──> AUTH_SUCCESS
 *   AUTH_SUCCESS ──(会话失效/网络变化)──> NEED_AUTH
 *   (非校园WiFi / 无WiFi ──> NOT_CAMPUS / NO_WIFI)
 */
enum class AuthStatus {
    /** 未连接 WiFi */
    NO_WIFI,
    /** 已连接 WiFi 但不是目标校园网 */
    NOT_CAMPUS,
    /** 校园网内，需要（或正在）认证 */
    NEED_AUTH,
    /** 已认证成功：不再提交登录，仅低频轻量探测会话 */
    AUTH_SUCCESS
}

object AuthGlobalState {

    @Volatile
    var currentStatus: AuthStatus = AuthStatus.NO_WIFI
        private set

    /** 会话探测连续明确离线次数（达到阈值才重新认证） */
    var sessionFailCount = 0
        private set

    /** 网络抖动计数（探测异常/不可达，达到阈值触发一次完整校验） */
    var sessionDirtyCount = 0
        private set

    /**
     * 切换状态：仅状态变化时写入（并持久化到 SharedPreferences）。
     * 注意：此方法内不做任何网络 IO，避免锁内阻塞。
     */
    @Synchronized
    fun setStatus(status: AuthStatus) {
        if (currentStatus != status) {
            currentStatus = status
            sessionFailCount = 0
            sessionDirtyCount = 0
            PrefUtils.saveAuthState(AppContext.get(), status.ordinal, System.currentTimeMillis())
        }
    }

    /** 服务重启恢复：直接还原内存状态，不重写持久化时间戳 */
    @Synchronized
    fun restore(status: AuthStatus) {
        currentStatus = status
        sessionFailCount = 0
        sessionDirtyCount = 0
    }

    @Synchronized
    fun addFail() {
        sessionFailCount++
    }

    @Synchronized
    fun addDirty() {
        sessionDirtyCount++
    }

    @Synchronized
    fun resetCounters() {
        sessionFailCount = 0
        sessionDirtyCount = 0
    }
}
