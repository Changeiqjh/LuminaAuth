package com.luminaauth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 常驻前台服务：
 * 保证后台自动登录可靠执行。
 * - 动态广播监听 WiFi 变化，立即触发自动认证
 * - 定时轮询兜底：即使广播未触发，也周期性检查校园网并自动认证
 * - 同一条常驻通知实时显示自动执行状态（检测到 WiFi / 等待就绪 / 认证中 / 成功 / 失败）
 */
public class AutoLoginService extends Service {

    private static final String CHANNEL_ID = "auto_login_service";
    private static final int NOTIFY_ID = 2001;

    /**
     * 低功耗轮询间隔（不在校园网时使用）。
     * 由设置页滑块全局联动：滑块值 × 3，钳制在 [10s, 60s]。
     * - 进入校园网主要由广播即时发现，轮询仅作兜底，因此离网后无需高频空转；
     * - 滑块调大/调小会等比影响所有轮询速度（含离网兜底），保证"全局可调"。
     */
    private static final long SLOW_POLL_MIN_MS = 10_000L;
    private static final long SLOW_POLL_MAX_MS = 60_000L;
    private static final int SLOW_POLL_MULTIPLIER = 3;

    /**
     * 已认证成功（AUTH_SUCCESS）状态下的低频会话探测间隔：
     * 滑块值 × 12，钳制在 [120s, 300s]（2~5 分钟一次）。
     * 认证成功后绝不高频查询，杜绝重复认证/重复查询；配合离线复核与高阈值，
     * 单次瞬时误判不会触发重认证。
     */
    private static final long SUCCESS_PROBE_MIN_MS = 120_000L;
    private static final long SUCCESS_PROBE_MAX_MS = 300_000L;
    private static final int SUCCESS_PROBE_MULTIPLIER = 12;

    /** WiFi 闪断防抖窗口：断开未满 3 秒视为闪断，不重置认证状态 */
    private static final long WIFI_FLAP_WINDOW_MS = 3_000L;

    /** 断网重连判定窗口：WiFi 重新在线且距最近一次断开在 (3s, 45s] 内视为"刚重连"，立即重新认证 */
    private static final long WIFI_RECONNECT_WINDOW_MS = 45_000L;

    /** 重连/通知信号触发立即认证的最小间隔：30 秒内只触发一次，防止反复重连刷认证 */
    private static final long REAUTH_MIN_GAP_MS = 30_000L;

    /** 服务重启后持久化认证状态的有效期（15 分钟），超时作废 */
    private static final long STATE_VALID_MS = 15 * 60 * 1000L;

    /**
     * 会话失效判定阈值（防误判）：
     * - 明确离线需连续 3 次（每次探测前都会复核一次，实际要求 6 次瞬时判定连续离线）
     * - 网络抖动需连续 5 次
     */
    private static final int FAIL_THRESHOLD = 3;
    private static final int DIRTY_THRESHOLD = 5;

    /** pollTask 最小执行间隔：防止广播/唤醒信号重复触发导致同一秒多次探测 */
    private static final long MIN_POLL_GAP_MS = 2_000L;

    // ==================== 认证执行器常量（原 AutoLoginReceiver，已并入前台服务） ====================

    /** 目标校园网默认 SSID 列表（配置列表读取失败时回退） */
    private static final String[] TARGET_SSIDS = {
            "ChinaNet-JLZG", "ChinaNet-JLZG-5G", "ChinaNet-JLZG-2.4G",
            "JLZG", "JLZG-5G", "JLZG-2.4G"
    };
    /** 最大重试次数（含首次） */
    private static final int MAX_ATTEMPTS = 3;
    /** 重试间隔 */
    private static final long RETRY_INTERVAL_MS = 5000;
    /** 等待 WiFi 就绪的最长时间（覆盖慢速连接） */
    private static final long WIFI_READY_TIMEOUT_MS = 25000;
    /** 就绪后的缓冲延迟，等认证链路稳定 */
    private static final long STABLE_BUFFER_MS = 5000;
    /** 等待有效 IP 的最长时间 */
    private static final long NETWORK_READY_TIMEOUT_MS = 8000;

    /** 认证防重入锁 + 冷却 */
    private static final Object LOGIN_LOCK = new Object();
    private static boolean loginInProgress = false;
    private static long lastLoginAt = 0L;
    /** 上次检测是否已处于目标校园网：用于识别"刚进入/重连"，此时跳过冷却立即认证 */
    private static boolean lastSeenTarget = false;
    private static final long LOGIN_COOLDOWN_MS = 20000;

    /** 按滑块值计算离网兜底轮询间隔 */
    private long computeSlowPollInterval() {
        long user = PrefUtils.getPollIntervalMs(this);
        long slow = user * SLOW_POLL_MULTIPLIER;
        if (slow < SLOW_POLL_MIN_MS) slow = SLOW_POLL_MIN_MS;
        if (slow > SLOW_POLL_MAX_MS) slow = SLOW_POLL_MAX_MS;
        return slow;
    }

    /** 按滑块值计算已认证成功状态的低频探测间隔 */
    private long computeSuccessProbeInterval() {
        long user = PrefUtils.getPollIntervalMs(this);
        long probe = user * SUCCESS_PROBE_MULTIPLIER;
        if (probe < SUCCESS_PROBE_MIN_MS) probe = SUCCESS_PROBE_MIN_MS;
        if (probe > SUCCESS_PROBE_MAX_MS) probe = SUCCESS_PROBE_MAX_MS;
        return probe;
    }

    /** 当前服务实例，供 restartPolling 调用 */
    private static AutoLoginService instance;

    /** 后台轮询线程（增强模式下 root 命令不阻塞主线程） */
    private android.os.HandlerThread bgThread;
    private Handler handler;

    /** 上次轮询是否处于目标校园网（用于状态变化日志 + 两级间隔切换） */
    private boolean lastPollInTarget = false;

    /** 上次轮询 WiFi 是否在线（用于记录断开时间戳、闪断防抖） */
    private boolean lastWifiOn = false;

    /** 增强模式下记录上次 BSSID（漫游检测） */
    private String lastBssid = null;

    /** 上次 pollTask 实际执行时间（最小间隔保护用） */
    private long lastPollRunAt = 0L;

    /** 上次监听器守护检查时间（增强模式 + 通知检测开启时，每 30s 检查一次监听连接） */
    private long lastListenerCheckAt = 0L;

    /**
     * 强制下一轮立即会话探测标志：
     * 通知检测模式捕获校园网关通知 / 外部 ACTION_TRIGGER_CHECK 唤醒时置位，
     * 使 AUTH_SUCCESS 状态下即使 BSSID 未变化（增强模式默认低频）也立即探测一次会话，
     * 让"网关主动推送下线通知"即时响应。
     */
    private boolean forceProbeNextRun = false;

    /**
     * 强制下一轮立即执行完整认证评估标志（通知信号 / 断网重连触发）：
     * 绕过"连续 3 次探测失败才重认证"的低频保护，直接进入认证流程
     * （performAutoLogin 内部会先探测，会话仍在线则跳过登录，不会重复认证）。
     */
    private boolean forceReauthNextRun = false;

    /** 通知检测模式下，通知中解析出的目标 SSID（供 pollTask 消费后执行认证） */
    private String pendingSsid = null;

    /** 重连/通知信号触发立即认证的时间戳（30 秒去重） */
    private long lastReauthAt = 0L;

    /**
     * 认证快速通道标志：
     * 通知信号 / 断网重连触发立即认证时置位，performAutoLogin 据此跳过认证前的
     * 5 秒稳定缓冲（此时网络刚就绪，无需再等），把"捕获信号 -> 完成登录"压缩到 1~3 秒。
     */
    private volatile boolean fastAuthRequested = false;

    // ==================== 通知检测信号处理（业务统一在服务内） ====================

    /** 通知检测捕获的校园网相关通知 key（登录成功后逐个删除） */
    private final java.util.HashSet<String> notifyKeys = new java.util.HashSet<>();
    /** 通知信号去重：同一条通知（同 key）在短窗口内重复到达不重复触发 */
    private String lastNotifyKey = null;
    private long lastNotifySignalAt = 0L;
    private static final long NOTIFY_MIN_GAP_MS = 3000L;

    /** 动态广播：WiFi 状态 / 连接性变化时立即让状态机重新评估 */
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AutoLoginService s = instance;
            if (s != null && s.handler != null) {
                s.handler.removeCallbacks(s.pollTask);
                s.handler.post(s.pollTask);
            }
        }
    };

    /**
     * 定时轮询：认证状态机驱动。
     * - 无 WiFi：切 NO_WIFI（含 3s 闪断防抖），低功耗兜底
     * - 非校园 WiFi：切 NOT_CAMPUS，低功耗兜底，绝不登录
     * - 校园 WiFi + NEED_AUTH：才执行完整认证登录
     * - 校园 WiFi + AUTH_SUCCESS：仅低频轻量探测会话，连续 2 次明确离线才重新认证
     * - 日志只在状态发生变化时输出，避免刷屏
     */
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            long nextInterval = computeSlowPollInterval();
            try {
                // 最小执行间隔保护：广播 / 唤醒信号可能重复触发 pollTask，
                // 2 秒内不重复执行，避免同一秒连续探测导致误判累计；
                // 通知信号（forceReauthNextRun）豁免该保护：必须立即响应
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastPollRunAt < MIN_POLL_GAP_MS && !forceReauthNextRun) {
                    handler.postDelayed(this, 1000);
                    return;
                }
                lastPollRunAt = nowMs;

                // 增强模式 + 通知检测开启：监听器守护（每 30s 检查一次，
                // 监听器被系统断开时用 root 能力自动重绑，保持通知检测链路可靠）
                if (PrefUtils.isEnhancedModeEnabled(AutoLoginService.this)
                        && PrefUtils.isNotifyDetectEnabled(AutoLoginService.this)
                        && nowMs - lastListenerCheckAt > 30000L) {
                    lastListenerCheckAt = nowMs;
                    if (!GatewayNotificationListener.isListenerConnected()) {
                        LogBuffer.add("通知检测", "增强模式守护：监听未连接，执行 root 重绑");
                        GatewayNotificationListener.requestRebind(AutoLoginService.this);
                    }
                }

                // 通知检测模式开启：禁用全部 WiFi/BSSID/状态机/低频探测逻辑，
                // 认证只由 onStartCommand 的通知信号驱动（通知内 SSID 命中目标列表才触发）；
                // 增强模式在此模式下仅承担通知监听器守护（上方），绝不独立触发认证。
                // 这里只消费通知信号执行认证 + 保持服务轮询存活
                if (PrefUtils.isNotifyDetectEnabled(AutoLoginService.this)) {
                    if (forceReauthNextRun) {
                        forceReauthNextRun = false;
                        String ssid = (pendingSsid != null && !pendingSsid.isEmpty())
                                ? pendingSsid : getCurrentSsid();
                        pendingSsid = null;
                        if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                            AuthGlobalState.INSTANCE.setStatus(AuthStatus.NEED_AUTH);
                            LogBuffer.add("通知检测", "通知触发认证（ssid=" + ssid + "）");
                            LogBuffer.addDetail("认证", "通知检测模式：执行自动认证（ssid=" + ssid + "）");
                            performAutoLogin(ssid);
                        } else {
                            LogBuffer.addDetail("通知检测", "无可用 SSID，跳过本次认证");
                        }
                    }
                    handler.postDelayed(this, computeSlowPollInterval());
                    return;
                }

                if (!PrefUtils.isAutoAuthEnabled(AutoLoginService.this)) {
                    handler.postDelayed(this, nextInterval);
                    return;
                }

                // 1. WiFi 在线状态 + 闪断防抖
                boolean wifiOn = isWifiConnected();
                long lastDiscTs = PrefUtils.getWifiDisconnectTs(AutoLoginService.this);
                if (!wifiOn) {
                    if (lastWifiOn) {
                        // 刚检测到断开：记录时间戳
                        lastDiscTs = System.currentTimeMillis();
                        PrefUtils.saveWifiDisconnectTs(AutoLoginService.this, lastDiscTs);
                    }
                    if (System.currentTimeMillis() - lastDiscTs < WIFI_FLAP_WINDOW_MS) {
                        // 闪断窗口内：不清状态，稍后重试
                        handler.postDelayed(this, 1500);
                        return;
                    }
                }
                lastWifiOn = wifiOn;
                // 断网重连判定：当前在线，且距最近一次断开超过闪断窗口、在 45 秒内
                // （SSID 延迟读取不影响：窗口期内只要读到目标 SSID 就会触发）
                boolean wifiReconnected = wifiOn && lastDiscTs > 0
                        && System.currentTimeMillis() - lastDiscTs > WIFI_FLAP_WINDOW_MS
                        && System.currentTimeMillis() - lastDiscTs <= WIFI_RECONNECT_WINDOW_MS;

                // 2. 读取当前 SSID 与状态
                String ssid = getCurrentSsid();
                AuthStatus status = AuthGlobalState.INSTANCE.getCurrentStatus();
                LogBuffer.addDetail("轮询", "wifiOn=" + wifiOn + " ssid=" + (ssid.isEmpty() ? "<空>" : ssid)
                        + " 状态=" + status + " 目标=" + (wifiOn && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid) && isTargetSsid(ssid)));

                if (!wifiOn) {
                    if (status != AuthStatus.NO_WIFI) {
                        AuthGlobalState.INSTANCE.setStatus(AuthStatus.NO_WIFI);
                        LogBuffer.add("监测", "未连接 WiFi，等待中");
                        LogBuffer.addDetail("状态", "NO_WIFI：WiFi 断开超过防抖窗口（3s），已切换状态");
                    }
                    nextInterval = computeSlowPollInterval();
                } else if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) {
                    // SSID 暂时读不到（定位服务未开 / WiFi 切换中）：
                    // 保持当前状态，短间隔下轮再判，避免误切离网状态
                    LogBuffer.addDetail("轮询", "SSID 读取为空/" + ssid + "（定位未开或切换中），保持状态=" + status);
                    nextInterval = Math.min(computeSlowPollInterval(), 3000);
                } else if (!isTargetSsid(ssid)) {
                    if (status != AuthStatus.NOT_CAMPUS) {
                        AuthGlobalState.INSTANCE.setStatus(AuthStatus.NOT_CAMPUS);
                        LogBuffer.add("监测", "非校园网 WiFi（" + ssid + "），等待中");
                        LogBuffer.addDetail("状态", "NOT_CAMPUS：SSID=" + ssid + " 不在目标列表");
                    }
                    nextInterval = computeSlowPollInterval();
                } else {
                    // 校园网内
                    nextInterval = PrefUtils.getPollIntervalMs(AutoLoginService.this);
                    // 通知信号（"登录到WLAN网络"等重连提示）或断网重连：
                    // 网关会话大概率已失效，立即执行完整认证评估
                    // （performAutoLogin 内部先探测，仍在线则跳过登录，不会重复认证）；
                    // 30 秒内只触发一次，防止反复重连刷认证
                    if ((forceReauthNextRun || wifiReconnected)
                            && System.currentTimeMillis() - lastReauthAt > REAUTH_MIN_GAP_MS) {
                        boolean reauthBySignal = forceReauthNextRun;
                        forceReauthNextRun = false;
                        lastReauthAt = System.currentTimeMillis();
                        fastAuthRequested = true; // 跳过认证前 5s 稳定缓冲，立即执行
                        AuthGlobalState.INSTANCE.setStatus(AuthStatus.NEED_AUTH);
                        LogBuffer.add("监测", (reauthBySignal ? "收到通知信号" : "检测到校园网重连")
                                + "（" + ssid + "），立即校验认证");
                        LogBuffer.addDetail("状态", "信号触发立即认证：状态 -> NEED_AUTH，执行认证（在线则跳过）");
                        performAutoLogin(ssid);
                    } else if (forceProbeNextRun) {
                        // 其他唤醒（WiFi 广播 / 开机自启）：强制立即会话探测
                        forceProbeNextRun = false;
                        LogBuffer.add("监测", "收到唤醒信号，立即校验会话");
                        LogBuffer.addDetail("探测", "唤醒触发：立即会话探测");
                        probeSessionAndMaybeReauth(ssid);
                        nextInterval = computeSuccessProbeInterval();
                    } else {
                        switch (status) {
                            case AUTH_SUCCESS:
                                // 增强模式：BSSID 变化立即探测（漫游检测）
                                boolean probed = false;
                                if (PrefUtils.isEnhancedModeEnabled(AutoLoginService.this)) {
                                    String bssid = getCurrentBssid();
                                    LogBuffer.addDetail("探测", "增强模式 BSSID 检测：当前=" + bssid
                                            + " 上次=" + lastBssid);
                                    if (bssid != null && !bssid.isEmpty() && !bssid.equals(lastBssid)) {
                                        lastBssid = bssid;
                                        LogBuffer.add("监测", "检测到接入点变化，重新校验会话");
                                        probeSessionAndMaybeReauth(ssid);
                                        probed = true;
                                    }
                                }
                                if (!probed) {
                                    // 定时低频兜底探测（含增强模式 BSSID 未变场景）：
                                    // 保证断开重连同一 AP、服务重启等情况下仍会周期性校验会话
                                    LogBuffer.addDetail("探测", "AUTH_SUCCESS：低频会话探测（间隔="
                                            + computeSuccessProbeInterval() / 1000 + "s）");
                                    probeSessionAndMaybeReauth(ssid);
                                }
                                nextInterval = computeSuccessProbeInterval();
                                break;
                            case NEED_AUTH:
                                // 确实需要认证：执行完整登录流程（内部先探测在线，在线则跳过登录）
                                LogBuffer.addDetail("认证", "NEED_AUTH：执行自动认证（ssid=" + ssid + "）");
                                performAutoLogin(ssid);
                                break;
                            default:
                                // NO_WIFI / NOT_CAMPUS -> 刚进入校园网：进入待认证流程
                                AuthGlobalState.INSTANCE.setStatus(AuthStatus.NEED_AUTH);
                                LogBuffer.addDetail("状态", "进入校园网（ssid=" + ssid + "），状态 -> NEED_AUTH");
                                performAutoLogin(ssid);
                                break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, nextInterval);
        }
    };

    /**
     * AUTH_SUCCESS 状态下的低频会话存活探测：
     * - 先等一个有效 IP（拿不到按抖动计数）
     * - online：会话有效，清空计数
     * - offline：立即复核一次（500ms 后重查），两次都明确离线才算一次失败
     * - unreachable/异常：网络抖动，计数 +1
     * 连续 FAIL_THRESHOLD 次失败 或 DIRTY_THRESHOLD 次抖动才重新认证，
     * 单次瞬时误判（网关瞬时返回 / 响应抖动）不会触发重认证。
     */
    private void probeSessionAndMaybeReauth(String ssid) {
        String ip = waitForValidIp(NETWORK_READY_TIMEOUT_MS);
        if (ip == null) {
            LogBuffer.addDetail("探测", "未拿到有效 IP（8s 等待超时），按抖动计数 dirty="
                    + (AuthGlobalState.INSTANCE.getSessionDirtyCount() + 1));
            AuthGlobalState.INSTANCE.addDirty();
            maybeReauthFromProbe(ssid);
            return;
        }
        try {
            String s1 = LoginService.checkNetworkStatus(ip);
            LogBuffer.addDetail("探测", "IP=" + ip + " chkstatus首判=" + s1
                    + "（fail=" + AuthGlobalState.INSTANCE.getSessionFailCount()
                    + " dirty=" + AuthGlobalState.INSTANCE.getSessionDirtyCount() + "）");
            if ("online".equals(s1)) {
                AuthGlobalState.INSTANCE.resetCounters();
                LogBuffer.addDetail("探测", "会话有效（online），清空计数");
                return;
            }
            if ("offline".equals(s1)) {
                // 复核一次：防瞬时误判（网关偶发返回未认证 / 会话表短暂不一致）
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
                String s2;
                try {
                    s2 = LoginService.checkNetworkStatus(ip);
                } catch (Exception e) {
                    s2 = null;
                }
                LogBuffer.addDetail("探测", "首判离线，500ms 后复核=" + s2);
                if ("online".equals(s2)) {
                    // 复核在线：刚才只是瞬时抖动
                    AuthGlobalState.INSTANCE.resetCounters();
                    LogBuffer.addDetail("探测", "复核在线：瞬时抖动，清空计数，不重认证");
                    return;
                }
                if ("offline".equals(s2)) {
                    AuthGlobalState.INSTANCE.addFail();
                } else {
                    AuthGlobalState.INSTANCE.addDirty();
                }
            } else {
                AuthGlobalState.INSTANCE.addDirty();
            }
        } catch (Exception e) {
            LogBuffer.addDetail("探测", "chkstatus 异常: " + e.getMessage() + "，按抖动计数");
            AuthGlobalState.INSTANCE.addDirty();
        }
        maybeReauthFromProbe(ssid);
    }

    /** 探测计数达到阈值才重新认证（performAutoLogin 内部会再判在线，已在线则不提交登录） */
    private void maybeReauthFromProbe(String ssid) {
        LogBuffer.addDetail("探测", "计数检查 fail=" + AuthGlobalState.INSTANCE.getSessionFailCount()
                + " dirty=" + AuthGlobalState.INSTANCE.getSessionDirtyCount()
                + " 阈值 fail=" + FAIL_THRESHOLD + " dirty=" + DIRTY_THRESHOLD);
        if (AuthGlobalState.INSTANCE.getSessionFailCount() >= FAIL_THRESHOLD
                || AuthGlobalState.INSTANCE.getSessionDirtyCount() >= DIRTY_THRESHOLD) {
            LogBuffer.add("认证", "会话疑似失效，重新校验认证");
            LogBuffer.addDetail("状态", "探测连续失败/抖动达到阈值，状态 -> NEED_AUTH 重新认证");
            AuthGlobalState.INSTANCE.setStatus(AuthStatus.NEED_AUTH);
            performAutoLogin(ssid);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 后台轮询线程：增强模式下 root 命令（su）不会阻塞主线程
        bgThread = new android.os.HandlerThread("autologin-poll");
        bgThread.start();
        handler = new Handler(bgThread.getLooper());
        LogBuffer.add("服务", "前台服务已启动（后台监测校园网）");
        startForeground(NOTIFY_ID, buildNotification("校园网自动认证", "后台监听校园网 WiFi，自动完成登录"));
        registerNetworkReceiver();
        // 增强模式开启时，把进程优先级拉到最高（root 写 oom_score_adj / renice）
        boostPriorityIfEnhanced(this);
        // 服务被杀重启后恢复上次认证状态（15 分钟内有效，避免重复认证）
        try {
            int savedOrd = PrefUtils.loadAuthState(this);
            long savedTs = PrefUtils.getAuthStateTime(this);
            if (savedOrd >= 0 && savedOrd < AuthStatus.values().length
                    && savedTs > 0 && System.currentTimeMillis() - savedTs < STATE_VALID_MS) {
                AuthGlobalState.INSTANCE.restore(AuthStatus.values()[savedOrd]);
                LogBuffer.add("服务", "已恢复认证状态: " + AuthGlobalState.INSTANCE.getCurrentStatus());
                LogBuffer.addDetail("状态", "服务重启恢复状态=" + AuthGlobalState.INSTANCE.getCurrentStatus()
                        + " 距上次保存=" + (System.currentTimeMillis() - savedTs) / 1000 + "s（阈值" + STATE_VALID_MS / 60000 + "min）");
            } else {
                LogBuffer.addDetail("状态", "无有效持久化状态（savedOrd=" + savedOrd
                        + ", savedTs=" + savedTs + "），从 NO_WIFI 冷启动");
            }
        } catch (Exception e) {
            LogBuffer.addDetail("状态", "状态恢复异常: " + e.getMessage());
        }
        // 由状态机轮询统一处理首轮检查（含状态恢复后的会话校验）
        handler.post(pollTask);
        // 服务重启（进程经历过生死）后，会话极可能已失效：
        // 强制做一次完整认证评估（performAutoLogin 内部先探测，在线则跳过登录），
        // 避免停在 AUTH_SUCCESS 等 3 次探测失败（最长数分钟）才恢复；
        // 通知检测模式开启时不启用：该模式下认证只由系统通知触发，不做 WiFi 主动评估
        if (PrefUtils.isAutoAuthEnabled(this) && !PrefUtils.isNotifyDetectEnabled(this)) {
            forceReauthNextRun = true;
            LogBuffer.addDetail("服务", "服务启动，已请求立即认证评估（在线则跳过登录）");
        }
        // 通知检测开启但监听服务未连接（被系统杀掉）时，主动请求系统重新绑定
        if (PrefUtils.isNotifyDetectEnabled(this)
                && !GatewayNotificationListener.isListenerConnected()) {
            LogBuffer.add("通知检测", "服务启动：通知监听未连接，请求系统重新绑定");
            GatewayNotificationListener.requestRebind(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 收到"立即检查"信号（通知检测 / WiFi 广播 / 外部唤醒 / 开机自启）：
        // 通知检测的业务（去重/防抖/触发探测）统一在这里处理，监听器只负责转发信号
        if (intent != null && AutoLoginReceiver.ACTION_TRIGGER_CHECK.equals(intent.getAction())) {
            // 通知检测信号：携带通知 key 与内容
            String notifyKey = intent.getStringExtra(GatewayNotificationListener.EXTRA_NOTIFY_KEY);
            if (notifyKey != null && !notifyKey.isEmpty()) {
                long now = System.currentTimeMillis();
                // 同一条通知（同 key）3 秒内重复到达 -> 去重跳过
                if (notifyKey.equals(lastNotifyKey) && now - lastNotifySignalAt < NOTIFY_MIN_GAP_MS) {
                    LogBuffer.addDetail("通知检测", "重复通知信号，跳过（key=" + notifyKey + "）");
                    return START_STICKY;
                }
                lastNotifyKey = notifyKey;
                lastNotifySignalAt = now;
                String notifyText = intent.getStringExtra(GatewayNotificationListener.EXTRA_NOTIFY_TEXT);
                LogBuffer.add("监测", "收到通知信号，立即校验认证");
                LogBuffer.addDetail("通知检测", "收到通知信号：key=" + notifyKey + " 文本=" + notifyText);
                if (PrefUtils.isNotifyDetectEnabled(this)) {
                    // 通知检测模式：从通知内容解析 SSID，必须命中高级设置目标列表才触发认证；
                    // 不命中 -> 跳过认证也不删通知（等系统下一条"需要认证"的通知弹出再触发）
                    String ssidFromNotify = extractSsidFromNotifyText(notifyText);
                    if (ssidFromNotify == null || ssidFromNotify.isEmpty()) {
                        LogBuffer.addDetail("通知检测", "通知中未解析到 SSID，跳过认证");
                        return START_STICKY;
                    }
                    if (!isTargetSsid(ssidFromNotify)) {
                        LogBuffer.add("通知检测", "通知 SSID【" + ssidFromNotify
                                + "】不在目标列表，跳过认证（不删通知）");
                        return START_STICKY;
                    }
                    LogBuffer.add("通知检测", "通知 SSID【" + ssidFromNotify + "】命中目标列表，执行认证");
                    notifyKeys.add(notifyKey);
                    pendingSsid = ssidFromNotify;
                    forceReauthNextRun = true;
                } else {
                    // 旧逻辑（通知检测关闭）：通知信号（"登录到WLAN网络"等重连提示）
                    // 视为"需要重新认证"的强信号，直接进入完整认证评估
                    // （内部先探测，在线则跳过登录），不等 3 次失败
                    notifyKeys.add(notifyKey);
                    forceReauthNextRun = true;
                }
            } else {
                // 其他唤醒（WiFi 广播 / 开机自启 / 外部触发）
                forceProbeNextRun = true;
            }
            if (handler != null) {
                handler.removeCallbacks(pollTask);
                handler.post(pollTask);
            }
        }
        return START_STICKY;
    }

    /**
     * 用户划掉最近任务卡片时（部分国产 ROM 会连前台服务一起杀）：
     * 若自动认证仍开启，立即以前台服务方式重启自己。
     * 回调发生时进程仍处于前台态，startForegroundService 合法；
     * 配合 START_STICKY + 开机自启广播，覆盖绝大多数无 root 被杀场景。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        try {
            if (PrefUtils.isAutoAuthEnabled(this)) {
                Intent restart = new Intent(this, AutoLoginService.class);
                restart.setPackage(getPackageName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restart);
                } else {
                    startService(restart);
                }
                LogBuffer.add("服务", "最近任务被划掉，已自动重启前台服务");
            }
        } catch (Exception e) {
            LogBuffer.add("服务", "任务被划掉后重启失败: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        LogBuffer.add("服务", "前台服务已停止");
        try {
            unregisterReceiver(networkReceiver);
        } catch (Exception ignored) {}
        if (handler != null) handler.removeCallbacks(pollTask);
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        super.onDestroy();
    }

    /** 让服务立即按新的检测间隔重新调度轮询（滑块调节时调用） */
    public static void restartPolling(Context context) {
        AutoLoginService s = instance;
        if (s != null && s.handler != null) {
            s.handler.removeCallbacks(s.pollTask);
            s.handler.post(s.pollTask);
        }
    }

    /**
     * 增强模式下把当前进程优先级拉到最高，防止后台被杀：
     * - root：写 /proc/pid/oom_score_adj = -17（"不可见"级，LMK 几乎不会回收）
     *         + renice -20（CPU 调度最高优先级）
     * - Shizuku（shell 权限）：通常无权提升，尽力尝试 renice，失败静默
     * 增强模式未开启时不做任何操作。
     */
    public static void boostPriorityIfEnhanced(Context context) {
        try {
            if (!PrefUtils.isEnhancedModeEnabled(context)) return;
            int pid = android.os.Process.myPid();
            if (RootUtils.isRootAvailable()) {
                boolean ok = RootUtils.boostProcessPriority();
                LogBuffer.add("服务", ok
                        ? "增强模式：进程优先级已拉满（oom_score_adj=-17, nice=-20, pid=" + pid + "）"
                        : "增强模式：进程优先级提升失败（su 可能未授权）");
            } else if (ShizukuUtils.isReady()) {
                // shell 权限提升 own nice 一般会被拒绝，尽力而为
                ShizukuUtils.exec("renice -n -20 -p " + pid);
                LogBuffer.add("服务", "增强模式（Shizuku）：已尝试提升进程优先级（shell 权限可能无效）");
            }
        } catch (Throwable ignored) {}
    }

    /** 更新同一条常驻通知的状态内容（各阶段实时反馈） */
    public static void updateStatus(Context context, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "校园网自动认证服务",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .setShowWhen(false)
                    // 同一通知后续只改内容，不重复响铃/震动
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "校园网自动认证服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void registerNetworkReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(networkReceiver, filter);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 检测与自动认证执行器（已并入前台服务） ====================

    /** 结果广播（前台 Activity 显示登录结果用） */
    private static final String ACTION_LOGIN_RESULT = "com.schoolautologin.LOGIN_RESULT";
    private static final String EXTRA_SUCCESS = "success";
    private static final String EXTRA_MESSAGE = "message";

    /**
     * 自动认证入口（前台服务内执行，供状态机 NEED_AUTH 分支调用）：
     * 完整流程：等待 WiFi 就绪 -> 状态检测 -> 需要时提交登录（含重试）-> 同步状态机。
     * 已在线时不做任何登录操作，杜绝重复认证。
     */
    private void performAutoLogin(final String ssid) {
        // 开关、凭证校验
        if (!PrefUtils.isAutoAuthEnabled(this)) return;
        final String[] cfg = PrefUtils.loadConfig(this);
        if (cfg == null) {
            LogBuffer.addDetail("认证", "未保存账号密码，跳过自动认证");
            return;
        }

        if (!isTargetSsid(ssid)) {
            // 不在校园网：标记为"已离开"，下次进入（含重连）将立即认证；
            // 只在状态从"校园网内 -> 校园网外"变化时打日志，避免轮询刷屏
            synchronized (LOGIN_LOCK) {
                boolean justLeft = lastSeenTarget;
                lastSeenTarget = false;
                if (justLeft) {
                    LogBuffer.add("监测", "已离开校园网，等待中（当前SSID=" + (ssid.isEmpty() ? "未知" : ssid) + "）");
                }
            }
            // 已连上 WiFi 却读不到目标 SSID -> 多半是定位服务未开启，明确提示而不是静默无反应
            if (isWifiConnected() && ssid.isEmpty()) {
                AutoLoginService.updateStatus(this, "校园网自动认证",
                        "已连接 WiFi，但无法读取网络名称，请在系统设置中开启定位服务");
            }
            LogBuffer.addDetail("认证", "SSID=" + ssid + " 不在目标校园网，不执行认证");
            return;
        }

        // 防重入 + 冷却：
        // - 固定持续监测下，若检测到"刚进入/重连校园网"（上次不在 -> 本次在），跳过冷却立即认证
        // - 持续在校园网内则受冷却保护，避免反复认证
        synchronized (LOGIN_LOCK) {
            if (loginInProgress) {
                LogBuffer.addDetail("认证", "上一次认证仍在进行中（防重入），跳过");
                return;
            }
            long now = System.currentTimeMillis();
            boolean justConnected = !lastSeenTarget;
            lastSeenTarget = true;
            if (!justConnected && now - lastLoginAt < LOGIN_COOLDOWN_MS) {
                LogBuffer.addDetail("认证", "冷却中跳过（距上次认证 " + (now - lastLoginAt) / 1000 + "s < "
                        + LOGIN_COOLDOWN_MS / 1000 + "s）");
                return;
            }
            loginInProgress = true;
            lastLoginAt = now;
        }

        final String username = cfg[0];
        final String password = cfg[1];
        final String isp = cfg[2];

        LogBuffer.add("监测", "检测到校园网 " + ssid + "，开始自动认证流程");
        LogBuffer.addDetail("认证", "自动认证开始：ssid=" + ssid + " isp=" + isp
                + " 账号=" + maskAccount(username));
        AutoLoginService.updateStatus(this, "校园网自动认证", "检测到 " + ssid + "，正在等待网络就绪...");

        new Thread(() -> {
            boolean success = false;
            String message = "未知错误";
            try {
                // 第 1 步：等待 WiFi 真正就绪（连上目标网络 + 拿到有效 IP）
                if (!waitForWifiReady(WIFI_READY_TIMEOUT_MS)) {
                    message = "WiFi 网络尚未就绪，稍后自动重试";
                    LogBuffer.add("认证", "网络就绪超时，稍后自动重试");
                    LogBuffer.addDetail("认证", "25s 内未等到目标 SSID + 有效 IP，本次放弃");
                    AutoLoginService.updateStatus(this, "校园网自动认证", "网络就绪超时，稍后自动重试");
                    notifyResult(false, message);
                    return;
                }
                // 第 2 步：缓冲等待认证链路稳定；
                // 通知信号 / 断网重连触发（fastAuthRequested）：网络刚就绪，跳过缓冲立即认证
                boolean skipStableBuffer = fastAuthRequested;
                fastAuthRequested = false;
                if (!skipStableBuffer) {
                    try {
                        Thread.sleep(STABLE_BUFFER_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                // 第 3 步：状态检测 + 认证（含重试）
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    if (attempt > 1) {
                        try { Thread.sleep(RETRY_INTERVAL_MS); } catch (InterruptedException e) { break; }
                    }
                    AutoLoginService.updateStatus(this, "校园网自动认证", "正在认证（第 " + attempt + "/" + MAX_ATTEMPTS + " 次）...");

                    String ip = waitForValidIp(NETWORK_READY_TIMEOUT_MS);
                    if (ip == null) {
                        message = "IP 未分配，等待重试";
                        LogBuffer.addDetail("认证", "第" + attempt + "次：未拿到有效 IP");
                        continue;
                    }
                    try {
                        String status = LoginService.checkNetworkStatus(ip);
                        LogBuffer.addDetail("认证", "第" + attempt + "次：IP=" + ip + " chkstatus=" + status);
                        if ("online".equals(status)) {
                            success = true;
                            message = "已在线，无需操作";
                            LogBuffer.addDetail("认证", "已在线上，无需提交登录");
                            break;
                        } else if ("offline".equals(status)) {
                            LoginService.LoginResult r = LoginService.doLogin(username, password, isp, ip);
                            success = r.success;
                            message = r.message;
                            LogBuffer.add("认证", "认证结果(第" + attempt + "次): " + message);
                            if (success) break;
                        } else {
                            message = "认证服务器暂不可达，等待重试";
                        }
                    } catch (Exception e) {
                        message = "异常: " + e.getMessage();
                        LogBuffer.add("认证", "认证异常: " + e.getMessage());
                        LogBuffer.addDetail("认证", "认证异常详情: " + e);
                    }
                }

                // 同步状态机：成功 -> AUTH_SUCCESS（此后只低频探测，不再提交登录）；
                // 失败 -> NEED_AUTH（下次轮询重试）
                AuthGlobalState.INSTANCE.setStatus(success
                        ? AuthStatus.AUTH_SUCCESS : AuthStatus.NEED_AUTH);
                LogBuffer.addDetail("状态", "认证结束 success=" + success + " 状态 -> "
                        + AuthGlobalState.INSTANCE.getCurrentStatus() + " msg=" + message);
                // 登录成功后：删除通知检测捕获的校园网相关通知
                // （如系统"登录到WLAN网络"连接提示），保持通知栏干净、避免重复回调；
                // 检测逻辑保持正常运行，等待下一条需要认证的通知弹出后再次触发认证
                if (success || "已在线，无需操作".equals(message)) {
                    clearCampusNotifications();
                }
                if (success) {
                    // 认证成功文案短暂展示后，恢复常驻通知默认文案
                    // （"校园网自动认证 / 后台监听校园网 WiFi，自动完成登录"）
                    final String flashMsg = message;
                    handler.postDelayed(() -> {
                        try {
                            AutoLoginService.updateStatus(this,
                                    "校园网自动认证", "后台监听校园网 WiFi，自动完成登录");
                        } catch (Throwable ignored) {}
                    }, 2000);
                }
                // 在线跳过（未提交登录）时不改动常驻通知文案，保持默认监听状态；
                // 只有真正提交登录成功/失败才更新常驻通知标题
                if (!"已在线，无需操作".equals(message)) {
                    AutoLoginService.updateStatus(this,
                            success ? "校园网自动认证成功" : "校园网自动认证失败", message);
                }
                notifyResult(success, message);
            } finally {
                synchronized (LOGIN_LOCK) {
                    loginInProgress = false;
                }
            }
        }).start();
    }

    /** 账号脱敏：保留前 2 位，其余打码，避免详细日志泄露完整账号 */
    private static String maskAccount(String account) {
        if (account == null || account.isEmpty()) return "<空>";
        if (account.length() <= 2) return account.charAt(0) + "***";
        return account.substring(0, 2) + "***";
    }

    /**
     * 登录成功后清除通知检测捕获的校园网相关通知：
     * 遍历服务内记录的 notifyKeys，调用通知监听服务逐个取消，并清空记录。
     * 尽力而为：部分 ROM 可能不允许第三方取消系统应用通知，失败仅写日志不影响认证。
     */
    private void clearCampusNotifications() {
        synchronized (notifyKeys) {
            if (!notifyKeys.isEmpty()) {
                int n = 0;
                for (String k : notifyKeys) {
                    GatewayNotificationListener.cancelNotificationByKey(k);
                    n++;
                }
                notifyKeys.clear();
                LogBuffer.add("通知检测", "登录成功，已清除 " + n + " 条校园网相关通知");
            }
        }
        // 兜底：扫描通知栏，清理所有残留的"登录到WLAN网络"类连接通知。
        // 不依赖是否捕获过信号（notifyKeys），覆盖监听断连期间残留的情况，
        // 保证认证完成后通知栏不留这类通知
        GatewayNotificationListener.cancelCampusNotificationsByScan(this);
    }

    /**
     * 获取当前连接 WiFi 的 SSID（异常安全）。
     * 增强模式开启时优先 root / Shizuku（shell 提权）读取，失败回退 Java API。
     * 后台限制 / 定位服务关闭时也能可靠拿到。
     */
    private String getCurrentSsid() {
        // 增强模式：优先 root，其次 Shizuku（shell 提权）读取
        if (PrefUtils.isEnhancedModeEnabled(this)) {
            try {
                if (RootUtils.isRootAvailable()) {
                    String s = RootUtils.getWifiSsidViaRoot();
                    if (s != null && !s.isEmpty()) return normalizeSsid(s);
                }
            } catch (Exception ignored) {}
            try {
                if (ShizukuUtils.isReady()) {
                    String s = ShizukuUtils.getWifiSsidViaShizuku(this);
                    if (s != null && !s.isEmpty()) return normalizeSsid(s);
                }
            } catch (Exception ignored) {}
        }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                return normalizeSsid(wifi.getConnectionInfo().getSSID());
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 获取当前连接 WiFi 的 BSSID（接入点 MAC，用于漫游检测，异常安全）。
     * 增强模式优先 root/Shizuku 读取，失败回退 Java API；获取失败返回 null（跳过漫游判断）。
     */
    private String getCurrentBssid() {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("BSSID:\\s*([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})");
        // 增强模式：优先 root，其次 Shizuku
        if (PrefUtils.isEnhancedModeEnabled(this)) {
            try {
                if (RootUtils.isRootAvailable()) {
                    String out = RootUtils.exec("dumpsys wifi");
                    if (out != null) {
                        java.util.regex.Matcher m = p.matcher(out);
                        if (m.find()) return m.group(1).toUpperCase(java.util.Locale.US);
                    }
                }
            } catch (Exception ignored) {}
            try {
                if (ShizukuUtils.isReady()) {
                    String out = ShizukuUtils.exec("dumpsys wifi");
                    if (out != null) {
                        java.util.regex.Matcher m = p.matcher(out);
                        if (m.find()) return m.group(1).toUpperCase(java.util.Locale.US);
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                String b = wifi.getConnectionInfo().getBSSID();
                if (b != null && !b.isEmpty()) return b.toUpperCase(java.util.Locale.US);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 判断当前是否已连接 WiFi（通过网络接口判断，无需权限） */
    private boolean isWifiConnected() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (n.startsWith("wlan")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 等待 WiFi 就绪（最多 timeoutMs）：已连上目标校园网 且 拿到有效 IP。
     */
    private boolean waitForWifiReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String ssid = getCurrentSsid();
            if (isTargetSsid(ssid)) {
                String ip = LoginService.getLocalIPv4(this);
                if (isValidIp(ip)) return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        return false;
    }

    /** 等待拿到有效 IP（最多 timeoutMs），每秒探测一次 */
    private String waitForValidIp(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String ip = LoginService.getLocalIPv4(this);
            if (isValidIp(ip)) return ip;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        String ip = LoginService.getLocalIPv4(this);
        return isValidIp(ip) ? ip : null;
    }

    /** 排除无效 IP：空、0.x、169.254.x */
    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if (ip.startsWith("0.") || ip.startsWith("169.254.")) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            try {
                Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** 结果通知：前台 Activity 打开时额外发送结果广播 */
    private void notifyResult(boolean success, String message) {
        if (MainActivity.foreground) {
            Intent i = new Intent(ACTION_LOGIN_RESULT);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_SUCCESS, success);
            i.putExtra(EXTRA_MESSAGE, message);
            sendBroadcast(i);
        }
    }

    /** 去除系统返回的 SSID 首尾引号 */
    private static String normalizeSsid(String ssid) {
        if (ssid == null) return "";
        String s = ssid.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * SSID 匹配适配：
     * - 完全一致（忽略大小写）必然命中
     * - 双向子串包含：适配运营商前缀/后缀变体（如配置 JLZG-5G 可匹配 ChinaNet-JLZG-5G、
     *   JLZG-5G-Guest 等实际 SSID；反之亦然）
     * - 任一侧短于 4 字符时仅允许完全匹配，避免过短串（如 "5G"）误匹配无关网络
     */
    private static boolean ssidMatches(String target, String actual) {
        if (target == null || target.isEmpty() || actual == null || actual.isEmpty()) return false;
        if (target.equalsIgnoreCase(actual)) return true;
        if (target.length() < 4 || actual.length() < 4) return false;
        String t = target.toLowerCase();
        String a = actual.toLowerCase();
        return a.contains(t) || t.contains(a);
    }

    /**
     * 从系统"登录到WLAN网络 xxx"通知文本中解析 SSID：
     * 支持 "登录到WLAN网络 ChinaNet-JLZG-5G" / "登录到WiFi网络 xxx" / "WLAN网络xxx" 等格式，
     * 取"网络"关键词后的第一个非空白 token；解析失败返回 null（不触发认证）。
     */
    private static String extractSsidFromNotifyText(String text) {
        if (text == null || text.isEmpty()) return null;
        int idx = text.indexOf("网络");
        if (idx < 0) return null;
        String rest = text.substring(idx + 2).trim();
        if (rest.isEmpty()) return null;
        String token = rest.split("[\\s，。、,.;:：]+")[0].trim();
        // 去掉尾部标点（如 "ChinaNet-JLZG-5G。"）
        while (!token.isEmpty()) {
            char c = token.charAt(token.length() - 1);
            if (c == '。' || c == '，' || c == ',' || c == '.' || c == '!' || c == '！'
                    || c == '？' || c == '?' || c == '：' || c == ';' || c == '；') {
                token = token.substring(0, token.length() - 1);
            } else break;
        }
        return token.isEmpty() ? null : token;
    }

    /** 判断 SSID 是否为目标校园网（优先用户配置列表，失败回退默认列表） */
    private boolean isTargetSsid(String ssid) {
        if (ssid == null || ssid.isEmpty()) return false;
        try {
            String[] targets = PrefUtils.getSsidArray(this);
            for (String t : targets) {
                if (ssidMatches(t, ssid)) return true;
            }
        } catch (Exception e) {
            for (String t : TARGET_SSIDS) {
                if (ssidMatches(t, ssid)) return true;
            }
        }
        return false;
    }
}
