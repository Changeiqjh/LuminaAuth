package com.luminaauth;

import android.content.Context;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Random;

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
        String url = "http://218.6.130.195:1333/drcom/chkstatus?callback=dr1002&jsVersion=4.X&v=3328&lang=zh";
        String resp = NetworkUtils.get(url);
        if (resp.contains("\"result\":1")) return "online";
        if (resp.contains("\"result\":0")) return "offline";
        return "unreachable";
    }

    public static LoginResult doLogin(String username, String password, String isp, String ip) throws Exception {
        // 从配置读取登录地址（用户可自定义）
        String url = PrefUtils.getLoginUrl(AppContext.get());
        // 如果配置地址不包含账号密码，则动态追加（兼容旧格式）
        if (!url.contains("user_account") && !url.contains("username")) {
            String account = ",1," + username + "@" + isp;
            int rand = new Random().nextInt(9000) + 1000;
            url = url + (url.contains("?") ? "&" : "?") +
                    "callback=dr1003" +
                    "&login_method=1" +
                    "&user_account=" + account +
                    "&user_password=" + password +
                    "&wlan_user_ip=" + ip +
                    "&wlan_user_ipv6=" +
                    "&wlan_user_mac=000000000000" +
                    "&wlan_ac_ip=218.89.190.11" +
                    "&wlan_ac_name=" +
                    "&jsVersion=4.1.3" +
                    "&terminal_type=2" +
                    "&lang=zh-cn" +
                    "&v=" + rand +
                    "&lang=zh";
        }
        String resp = NetworkUtils.get(url);
        int start = resp.indexOf('{');
        int end = resp.lastIndexOf('}');
        if (start == -1 || end == -1) {
            return new LoginResult(false, "响应格式错误");
        }
        String jsonStr = resp.substring(start, end + 1);
        JSONObject json = new JSONObject(jsonStr);
        int result = json.getInt("result");
        String msg = json.optString("msg", "");
        if (result == 1) {
            return new LoginResult(true, "认证成功");
        } else if (result == 0) {
            if (msg.contains("已经在线")) {
                return new LoginResult(true, "已在线，无需操作");
            } else {
                return new LoginResult(false, "登录失败: " + msg);
            }
        } else {
            return new LoginResult(false, "未知响应: " + jsonStr);
        }
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
