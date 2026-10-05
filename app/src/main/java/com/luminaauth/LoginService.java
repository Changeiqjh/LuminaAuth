package com.luminaauth;

import android.content.Context;
import android.net.wifi.WifiManager;
import com.luminaauth.plugin.Outcome;
import com.luminaauth.plugin.Plugin;
import com.luminaauth.plugin.PluginManager;
import com.luminaauth.plugin.PluginRuntime;
import com.luminaauth.plugin.SessionState;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LoginService {

    public static String getLocalIPv4(Context context) {
        // 1. WifiManager 直读（有定位权限时最直接）
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." +
                            ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
                }
            }
        } catch (Exception ignored) {}
        // 2. 遍历网络接口：优先 WiFi 接口（wlan*）与私有网段 IP
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String ifName = ni.getName() == null ? "" : ni.getName().toLowerCase();
                for (java.net.InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ifName.startsWith("wlan") || ip.startsWith("192.168.")
                            || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception ignored) {}
        return null;
    }

    public static String checkNetworkStatus(String ip) throws Exception {
        // Plugin-driven status check: the request URL and match rules come
        // from the active plugin; only the outcome token is mapped back.
        Plugin plugin = PluginManager.INSTANCE.active(AppContext.get());
        SessionState state = new SessionState(plugin, new HashMap<>(), null);
        state.setIp(ip);
        Outcome outcome = PluginRuntime.INSTANCE.check(state);
        if (outcome == Outcome.ONLINE) return "online";
        if (outcome == Outcome.OFFLINE) return "offline";
        return "unreachable";
    }

    public static LoginResult doLogin(String username, String password, String isp, String ip) throws Exception {
        // Plugin-driven login: the active plugin defines the request template,
        // success/already-online rules and the failure message path.
        Plugin plugin = PluginManager.INSTANCE.active(AppContext.get());
        Map<String, String> inputs = new HashMap<>();
        inputs.put(Plugin.FIELD_USERNAME, username);
        inputs.put(Plugin.FIELD_PASSWORD, password);
        // Accept both a bare suffix ("after") and a historical one ("@after").
        String suffix = (isp == null) ? null : (isp.startsWith("@") ? isp.substring(1) : isp);
        SessionState state = new SessionState(plugin, inputs, suffix);
        state.setIp(ip);
        state.setLoginUrlOverride(PluginRuntime.INSTANCE.getEffectiveLoginUrl(
                AppContext.get(), PrefUtils.getLoginUrl(AppContext.get())));
        Outcome outcome = PluginRuntime.INSTANCE.login(state);
        if (outcome == Outcome.SUCCESS) {
            return new LoginResult(true, "认证成功");
        }
        if (outcome == Outcome.ALREADY_ONLINE) {
            return new LoginResult(true, "已在线，无需操作");
        }
        String detail = (state.getDetail() == null || state.getDetail().isEmpty())
                ? state.getReason() : state.getDetail();
        return new LoginResult(false, "登录失败: " + detail);
    }

    public static class LoginResult {
        public final boolean success;
        public final String message;
        public LoginResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
