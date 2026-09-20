package com.luminaauth

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知检测模式——信号捕获器（不含任何业务逻辑）：
 * 只负责监听系统通知、过滤与校园网认证网关相关的通知（网页登录页 / 认证失败 /
 * 下线提示 / 系统"登录到WLAN网络"连接提示等），捕获到匹配通知后把信号
 * （通知 key + 内容）转发给 AutoLoginService 前台服务。
 *
 * 去重 / 防抖 / 触发会话探测 / 登录成功后删除通知等全部业务逻辑
 * 统一由 AutoLoginService 前台服务内的状态机处理，与 WiFi 广播触发共用同一入口，
 * 保证逻辑一致、单一权威来源。
 */
class GatewayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        LogBuffer.add("通知检测", "通知监听已连接（已获得通知使用权）")
        // 连接恢复后，清理监听断连期间残留的"登录到WLAN网络"类通知
        // （延迟一小段，等系统通知列表稳定后再扫描）
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { scanAndCancelWlanNotifications() }, 1200
            )
        } catch (e: Exception) {
            // 个别 ROM 可能异常，忽略
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        LogBuffer.add("通知检测", "通知监听已断开（可能被用户在系统设置中关闭）")
    }

    /**
     * 扫描通知栏，删除所有匹配的"登录到WLAN网络"类系统连接通知。
     * 不依赖是否捕获过信号（notifyKeys），覆盖监听断连期间残留的情况。
     */
    private fun scanAndCancelWlanNotifications() {
        try {
            val actives = activeNotifications ?: return
            var n = 0
            for (sbn in actives) {
                if (isWlanLoginNotification(sbn)) {
                    try {
                        cancelNotification(sbn.key)
                        n++
                    } catch (e: Exception) {
                        LogBuffer.addDetail("通知检测", "扫描删除通知失败: " + e.message)
                    }
                }
            }
            if (n > 0) {
                LogBuffer.add("通知检测", "已清理 " + n + " 条残留的 WLAN 登录通知")
            }
        } catch (e: Exception) {
            LogBuffer.addDetail("通知检测", "扫描清理通知异常: " + e.message)
        }
    }

    /** 判断是否为"登录到WLAN网络"类连接通知（排除本应用自己的通知，避免误删常驻服务通知） */
    private fun isWlanLoginNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        // 只清理系统框架（android 包）发出的 WLAN 连接通知，绝不误删第三方通知（短信验证码等）
        if (sbn.packageName != "android") return false
        val text = extractText(sbn) ?: return false
        val t = text.lowercase()
        // 特征：标题/正文含 "WLAN"/"WiFi" 且含 "登录"（"登录到WLAN网络 xxx"）
        // 本应用常驻通知虽含"WiFi/登录"但已被上方包名排除，不会误删
        return (t.contains("wlan") || t.contains("wifi")) && t.contains("登录")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // 开关校验：通知检测已开启 + 自动认证已开启
        if (!PrefUtils.isNotifyDetectEnabled(this)) return
        if (!PrefUtils.isAutoAuthEnabled(this)) return
        // 过滤本应用自己的常驻服务通知，避免自触发死循环
        if (sbn.packageName == packageName) return
        // 只处理系统框架（android 包）发出的 WLAN 连接通知；
        // 短信/微信/其他应用通知一律忽略：不触发认证、不参与任何后续逻辑
        if (sbn.packageName != "android") {
            LogBuffer.addDetail("通知过滤", "跳过非系统包通知：package=" + sbn.packageName)
            return
        }
        val text = extractText(sbn) ?: return
        if (!containsKeyword(text)) return
        // 只做捕获与信号转发；去重/防抖/触发探测由 AutoLoginService 统一处理
        LogBuffer.add("通知检测", "捕获校园网关通知（" + sbn.packageName + "）：" + clip(text))
        LogBuffer.addDetail("通知检测", "转发信号给前台服务：key=" + sbn.key
                + " 内容=" + clip(text, 200))
        try {
            val intent = Intent(this, AutoLoginService::class.java)
            intent.action = AutoLoginReceiver.ACTION_TRIGGER_CHECK
            intent.putExtra(EXTRA_NOTIFY_KEY, sbn.key)
            intent.putExtra(EXTRA_NOTIFY_TEXT, text)
            intent.setPackage(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            LogBuffer.addDetail("通知检测", "唤醒前台服务失败: " + e.message)
        }
    }

    /** 拼接通知的标题 + 正文 + 大文本，用于关键词匹配 */
    private fun extractText(sbn: StatusBarNotification): String? {
        val extras = sbn.notification?.extras ?: return null
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val combined = (title + " " + text + " " + big).trim()
        return combined.ifEmpty { null }
    }

    /** 信号预过滤：内容命中校园网相关关键词才转发（低成本过滤，避免全量通知打扰服务） */
    private fun containsKeyword(text: String): Boolean {
        for (k in KEYWORDS) {
            if (text.contains(k)) return true
        }
        return false
    }

    private fun clip(s: String, max: Int = 80): String =
        if (s.length <= max) s else s.substring(0, max) + "…"

    companion object {
        /** 转发给 AutoLoginService 的 Intent 附加字段名 */
        const val EXTRA_NOTIFY_KEY = "notify_key"
        const val EXTRA_NOTIFY_TEXT = "notify_text"

        /** 校园网认证网关通知的关键词特征（命中任意一个即视为"可能需要重新认证"的信号） */
        private val KEYWORDS = arrayOf(
            "登录", "认证", "下线", "断网", "未认证", "请登录", "上网认证",
            "portal", "Portal", "eportal", "校园网", "欠费", "到期", "流量不足"
        )

        @Volatile
        private var instance: GatewayNotificationListener? = null

        // ==================== 重绑互斥与冷却 ====================
        /** 重绑流程全局锁：同一时间只允许一个重绑在跑（避免多触发源并发改写系统设置） */
        private val rebindLock = Any()
        /** 是否有重绑流程正在执行 */
        private var rebindRunning = false
        /** 上次重绑开始时间（30s 冷却，防止高频重试） */
        private var lastRebindStartAt = 0L
        private const val REBIND_COOLDOWN_MS = 30000L

        /** 通知监听服务当前是否已连接（进程内 instance 是否存活） */
        @JvmStatic
        fun isListenerConnected(): Boolean = instance != null

        /**
         * 请求系统重新绑定通知监听服务：
         * 国产 ROM 会杀掉通知监听组件导致收不到通知。三级恢复策略：
         * 1) 官方 API requestBindListener（反射调用，部分 ROM 已移除）；
         * 2) 组件开关 toggle（DISABLED -> ENABLED，无需任何权限、全 ROM 通用低成本尝试）；
         * 3) 增强模式（root）开启时：cmd notification allow_listener 直接通知系统服务
         *    强绑定监听器（无需改 settings、不碰系统界面）；
         *    增强模式未开启 / 无 root：不执行任何 root 命令，直接跳转系统设置手动重新开关。
         * 注意：全局互斥 + 30s 冷却——同一时间只允许一个重绑流程执行。
         * 整个恢复流程在后台线程执行，不阻塞主线程调用方（设置页 LaunchedEffect 在主线程）。
         * 由服务启动 / 设置页 / 定时守护调用，静默请求、无副作用。
         */
        @JvmStatic
        fun requestRebind(context: Context) {
            // 监听已连接：无需重绑，直接跳过（避免无意义操作）
            if (isListenerConnected()) {
                LogBuffer.addDetail("通知检测", "监听已连接，无需重绑")
                return
            }
            // 互斥 + 冷却：重绑进行中或 30s 内刚执行过 -> 跳过本次请求
            synchronized(rebindLock) {
                val now = System.currentTimeMillis()
                if (rebindRunning || now - lastRebindStartAt < REBIND_COOLDOWN_MS) {
                    LogBuffer.addDetail("通知检测", "重绑进行中/冷却中，跳过本次请求")
                    return
                }
                rebindRunning = true
                lastRebindStartAt = now
            }
            Thread {
                try {
                    // 方案 1：官方 API（反射，若该 ROM 仍保留）
                    var rebound = false
                    try {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        if (nm != null) {
                            val cn = ComponentName(context, GatewayNotificationListener::class.java)
                            val method = NotificationManager::class.java
                                    .getMethod("requestBindListener", ComponentName::class.java)
                            method.invoke(nm, cn)
                            LogBuffer.addDetail("通知检测", "已调用系统 requestBindListener 请求重新绑定")
                            // 等待系统异步重绑结果（最多 2s）
                            for (i in 1..20) {
                                Thread.sleep(100)
                                if (isListenerConnected()) {
                                    rebound = true
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogBuffer.addDetail("通知检测", "系统 requestBindListener 不可用：" + e.message)
                    }
                    // 方案 2：组件开关 toggle（无需任何权限，全 ROM 通用低成本尝试）
                    if (!rebound && !isListenerConnected()) {
                        rebound = tryToggleComponentRebind(context)
                    }
                    // 方案 3：增强模式（root）强绑定
                    // 增强模式未开启 / 无 root：完全不碰 root 命令，直接跳转系统设置
                    if (!rebound && !isListenerConnected()) {
                        if (PrefUtils.isEnhancedModeEnabled(context) && RootUtils.isRootAvailable()) {
                            rebound = tryRootRebind(context)
                        } else {
                            LogBuffer.addDetail("通知检测",
                                    if (!PrefUtils.isEnhancedModeEnabled(context)) "增强模式未开启，不执行 root 命令"
                                    else "无 root，无法强绑定")
                            openListenerSettings(context)
                        }
                    }
                    if (rebound || isListenerConnected()) {
                        LogBuffer.add("通知检测", "通知监听重绑成功")
                    }
                } catch (e: Exception) {
                    LogBuffer.addDetail("通知检测", "通知监听重绑异常: " + e.message)
                    openListenerSettings(context)
                } finally {
                    synchronized(rebindLock) { rebindRunning = false }
                }
            }.start()
        }

        /**
         * 组件开关 toggle：通过 PackageManager 将监听组件 DISABLED -> ENABLED，
         * 触发系统重新解析组件列表并尝试重新绑定监听器。
         * 无需任何权限；原生 Android 大概率生效，部分国产 ROM 会无视，作为低成本尝试。
         * @return 切换后监听是否已恢复连接
         */
        private fun tryToggleComponentRebind(context: Context): Boolean {
            return try {
                val pm = context.packageManager
                val cn = ComponentName(context, GatewayNotificationListener::class.java)
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Thread.sleep(300)
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                // 等待系统重新绑定（最多 3s）
                for (i in 1..30) {
                    Thread.sleep(100)
                    if (isListenerConnected()) return true
                }
                LogBuffer.addDetail("通知检测", "组件开关 toggle 后监听仍未恢复，继续后续方案")
                false
            } catch (e: Exception) {
                LogBuffer.addDetail("通知检测", "组件开关 toggle 失败: " + e.message)
                false
            }
        }

        /**
         * root 强绑定：cmd notification allow_listener 直接通知系统 NotificationManagerService
         * 把我们的监听组件拉起来并建立绑定（root 下官方系统命令，不改设置、不碰系统界面）。
         * @return 是否强绑定成功
         */
        private fun tryRootRebind(context: Context): Boolean {
            return try {
                val flat = ComponentName(context, GatewayNotificationListener::class.java)
                        .flattenToString()
                var connected = false
                for (attempt in 1..2) {
                    LogBuffer.addDetail("通知检测",
                            "root 强绑定第 $attempt 次：cmd notification allow_listener $flat")
                    RootUtils.exec("cmd notification allow_listener \"$flat\"")
                    Thread.sleep(2000)
                    if (isListenerConnected()) {
                        connected = true
                        break
                    }
                }
                if (connected) {
                    LogBuffer.add("通知检测", "root cmd notification 强绑定成功")
                } else {
                    LogBuffer.add("通知检测", "root 强绑定重试失败，跳转系统设置请手动重新开关通知使用权")
                    openListenerSettings(context)
                }
                connected
            } catch (e: Exception) {
                LogBuffer.addDetail("通知检测", "root 强绑定失败: " + e.message)
                openListenerSettings(context)
                false
            }
        }

        /** 打开系统"读取、回复和控制通知"（通知使用权）设置界面，引导用户手动重新开关 */
        private fun openListenerSettings(context: Context) {
            try {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (e: Exception) {
                LogBuffer.addDetail("通知检测", "打开通知使用权设置失败: " + e.message)
            }
        }

        /**
         * 按通知 key 删除指定通知（登录成功后由 AutoLoginService 调用）。
         * 通过 NotificationListenerService.cancelNotification 取消（尽力而为，
         * 部分 ROM 可能不允许第三方取消系统应用通知，失败仅写日志不影响认证）。
         */
        @JvmStatic
        fun cancelNotificationByKey(key: String?) {
            if (key == null || key.isEmpty()) return
            val listener = instance ?: run {
                LogBuffer.addDetail("通知检测", "通知监听未连接，无法清除通知（通知栏可能残留，通知触发链路也可能已失效）")
                return
            }
            try {
                listener.cancelNotification(key)
            } catch (e: Exception) {
                LogBuffer.addDetail("通知检测", "取消通知失败: " + e.message)
            }
        }

        /**
         * 扫描通知栏并清理所有"登录到WLAN网络"类连接通知（认证成功后由 AutoLoginService 调用）。
         * 兜底方案：不依赖认证过程中是否捕获过该通知的信号（notifyKeys），
         * 即使监听断连期间残留的通知也能在认证成功后一并清掉。
         */
        @JvmStatic
        fun cancelCampusNotificationsByScan(context: Context) {
            val listener = instance ?: run {
                LogBuffer.addDetail("通知检测", "通知监听未连接，无法扫描清理通知（认证已完成，但通知栏可能残留）")
                return
            }
            listener.scanAndCancelWlanNotifications()
        }

        /** 当前应用是否已被授予通知使用权（通知监听权限） */
        @JvmStatic
        fun isAccessGranted(context: Context): Boolean {
            val cn = ComponentName(context, GatewayNotificationListener::class.java)
            // API 33+：使用系统提供的判断接口
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return false
                return try {
                    nm.isNotificationListenerAccessGranted(cn)
                } catch (e: Exception) {
                    false
                }
            }
            // 旧版本：读取系统设置中的已启用通知监听器列表（冒号分隔的 flattened ComponentName）
            val flat = cn.flattenToString()
            val enabled = try {
                // SDK 37 起 Settings.Secure.ENABLED_NOTIFICATION_LISTENERS 常量被移除，用系统键名字面量
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
            } catch (e: Exception) {
                null
            }
            return enabled?.split(":")?.any { it.equals(flat, ignoreCase = true) } ?: false
        }
    }
}
