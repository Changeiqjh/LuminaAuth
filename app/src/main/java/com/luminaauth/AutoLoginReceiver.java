package com.luminaauth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 自动登录执行器：
 * - 由 AutoLoginService（动态广播 + 定时轮询）和 MainActivity 调用 performAutoLogin()
 * - 关键优化：连上 WiFi 却读不到名称（定位服务未开）时明确提示用户，
 *   避免"读不到 SSID 导致自动登录完全无反应"的困惑
 * - 认证各阶段状态实时更新到同一条常驻通知，用户随时可见执行进展
 * - 防重入锁 + 冷却，避免重复认证
 */
public class AutoLoginReceiver extends BroadcastReceiver {

    public static final String ACTION_LOGIN_RESULT = "com.schoolautologin.LOGIN_RESULT";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_MESSAGE = "message";

    /** 目标校园网 SSID 列表 */
    public static final String[] TARGET_SSIDS = {
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

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        boolean networkChanged = WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action);
        boolean suppChanged = WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION.equals(action);
        boolean connectivityChanged = "android.net.conn.CONNECTIVITY_CHANGE".equals(action);
        if (!networkChanged && !suppChanged && !connectivityChanged) return;
        performAutoLogin(context);
    }

    /** 公共自动登录入口（广播 / 定时轮询 / 手动触发共用） */
    public static void performAutoLogin(final Context context) {
        // 开关、凭证校验
        if (!PrefUtils.isAutoAuthEnabled(context)) return;
        final String[] cfg = PrefUtils.loadConfig(context);
        if (cfg == null) return;

        // 读取当前 SSID 并匹配
        final String ssid = getCurrentSsid(context);
        if (!isTargetSsid(ssid)) {
            // 不在校园网：标记为"已离开"，下次进入（含重连）将立即认证
            synchronized (LOGIN_LOCK) {
                lastSeenTarget = false;
            }
            LogBuffer.add("监测", "不在校园网，等待中（当前SSID=" + (ssid.isEmpty() ? "未知" : ssid) + "）");
            // 已连上 WiFi 却读不到目标 SSID → 多半是定位服务未开启，明确提示而不是静默无反应
            if (isWifiConnected(context) && ssid.isEmpty()) {
                AutoLoginService.updateStatus(context, "校园网自动认证",
                        "已连接 WiFi，但无法读取网络名称，请在系统设置中开启定位服务");
            }
            return;
        }

        // 防重入 + 冷却：
        // - 固定持续监测下，若检测到"刚进入/重连校园网"（上次不在 -> 本次在），跳过冷却立即认证
        // - 持续在校园网内则受冷却保护，避免反复认证
        synchronized (LOGIN_LOCK) {
            if (loginInProgress) return;
            long now = System.currentTimeMillis();
            boolean justConnected = !lastSeenTarget;
            lastSeenTarget = true;
            if (!justConnected && now - lastLoginAt < LOGIN_COOLDOWN_MS) return;
            loginInProgress = true;
            lastLoginAt = now;
        }

        final String username = cfg[0];
        final String password = cfg[1];
        final String isp = cfg[2];

        LogBuffer.add("监测", "检测到校园网 " + ssid + "，开始自动认证流程");
        AutoLoginService.updateStatus(context, "校园网自动认证", "检测到 " + ssid + "，正在等待网络就绪...");

        new Thread(() -> {
            boolean success = false;
            String message = "未知错误";
            try {
                // 第 1 步：等待 WiFi 真正就绪（连上目标网络 + 拿到有效 IP）
                if (!waitForWifiReady(context, WIFI_READY_TIMEOUT_MS)) {
                    message = "WiFi 网络尚未就绪，稍后自动重试";
                    LogBuffer.add("认证", "网络就绪超时，稍后自动重试");
                    AutoLoginService.updateStatus(context, "校园网自动认证", "网络就绪超时，稍后自动重试");
                    notifyResult(context, false, message);
                    return;
                }
                // 第 2 步：缓冲等待认证链路稳定
                try {
                    Thread.sleep(STABLE_BUFFER_MS);
                } catch (InterruptedException e) {
                    return;
                }

                // 第 3 步：状态检测 + 认证（含重试）
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    if (attempt > 1) {
                        try { Thread.sleep(RETRY_INTERVAL_MS); } catch (InterruptedException e) { break; }
                    }
                    AutoLoginService.updateStatus(context, "校园网自动认证", "正在认证（第 " + attempt + "/" + MAX_ATTEMPTS + " 次）...");

                    String ip = waitForValidIp(context, NETWORK_READY_TIMEOUT_MS);
                    if (ip == null) {
                        message = "IP 未分配，等待重试";
                        continue;
                    }
                    try {
                        String status = LoginService.checkNetworkStatus(ip);
                        if ("online".equals(status)) {
                            success = true;
                            message = "已在线，无需操作";
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
                    }
                }

                AutoLoginService.updateStatus(context,
                        success ? "校园网自动认证成功" : "校园网自动认证失败", message);
                notifyResult(context, success, message);
            } finally {
                synchronized (LOGIN_LOCK) {
                    loginInProgress = false;
                }
            }
        }).start();
    }

    /**
     * 获取当前连接 WiFi 的 SSID（异常安全）。
     * 增强模式开启时用 Shizuku（shell 提权）读取，失败回退 Java API。
     * 后台限制 / 定位服务关闭时也能可靠拿到。
     */
    public static String getCurrentSsid(Context context) {
        // 增强模式：优先 root，其次 Shizuku（shell 提权）读取
        if (PrefUtils.isEnhancedModeEnabled(context)) {
            try {
                if (RootUtils.isRootAvailable()) {
                    String s = RootUtils.getWifiSsidViaRoot();
                    if (s != null && !s.isEmpty()) return normalizeSsid(s);
                }
            } catch (Exception ignored) {}
            try {
                if (ShizukuUtils.isReady()) {
                    String s = ShizukuUtils.getWifiSsidViaShizuku(context);
                    if (s != null && !s.isEmpty()) return normalizeSsid(s);
                }
            } catch (Exception ignored) {}
        }
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                return normalizeSsid(wifi.getConnectionInfo().getSSID());
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 判断当前是否已连接 WiFi（通过网络接口判断，无需权限） */
    public static boolean isWifiConnected(Context context) {
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
    private static boolean waitForWifiReady(Context context, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String ssid = getCurrentSsid(context);
            if (isTargetSsid(ssid)) {
                String ip = LoginService.getLocalIPv4(context);
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
    private static String waitForValidIp(Context context, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String ip = LoginService.getLocalIPv4(context);
            if (isValidIp(ip)) return ip;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        String ip = LoginService.getLocalIPv4(context);
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

    /** 结果通知：更新同一条常驻通知（内容实时变化），前台额外弹 AlertDialog */
    private static void notifyResult(Context context, boolean success, String message) {
        if (MainActivity.foreground) {
            Intent i = new Intent(ACTION_LOGIN_RESULT);
            i.setPackage(context.getPackageName());
            i.putExtra(EXTRA_SUCCESS, success);
            i.putExtra(EXTRA_MESSAGE, message);
            context.sendBroadcast(i);
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

    public static boolean isTargetSsid(String ssid) {
        for (String t : TARGET_SSIDS) {
            if (t.equalsIgnoreCase(ssid)) return true;
        }
        return false;
    }
}
