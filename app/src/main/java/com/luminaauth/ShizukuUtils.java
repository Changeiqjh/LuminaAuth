package com.luminaauth;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Shizuku（shell 提权）工具类：
 * 通过 Shizuku UserService 以 adb shell 权限执行系统命令，读取当前 WiFi SSID。
 * 前提：设备已安装并启动 Shizuku（adb shell sh .../start.sh 或 root 方式启动），
 * 且本应用已在 Shizuku 中授权。
 * shell 权限下 cmd wifi status / dumpsys wifi 可正常读取连接信息。
 */
public class ShizukuUtils {

    private static IShell shellBinder;
    private static boolean binding = false;

    /** Shizuku 服务是否已连接 */
    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本应用是否已获得 Shizuku 授权 */
    public static boolean isPermissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已就绪（连接 + 授权） */
    public static boolean isReady() {
        return isShizukuAvailable() && isPermissionGranted();
    }

    /** 绑定 Shizuku UserService（在 shell 权限进程运行 ShellUserService），幂等 */
    public static synchronized void init(Context context) {
        try {
            if (!isReady() || shellBinder != null || binding) return;
            binding = true;
            ComponentName componentName = new ComponentName(context, ShellUserService.class);
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(componentName);
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            binding = false;
        }
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            shellBinder = IShell.Stub.asInterface(service);
            binding = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shellBinder = null;
            binding = false;
        }
    };

    /**
     * 通过 Shizuku 以 shell 权限执行命令，返回 stdout（失败/未就绪返回 null）。
     */
    public static String exec(String command) {
        try {
            if (shellBinder == null) return null;
            return shellBinder.exec(command);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用 Shizuku 读取当前 WiFi SSID。
     * 优先 cmd wifi status（快速），失败则 dumpsys wifi（通用）。
     */
    public static String getWifiSsidViaShizuku(Context context) {
        init(context);
        String out = exec("cmd wifi status");
        String ssid = parseConnectedSsid(out);
        if (ssid != null && !ssid.isEmpty()) return ssid;

        out = exec("dumpsys wifi");
        if (out != null) {
            Matcher m = Pattern.compile("SSID:\\s*\"?([^\",\\s]+)").matcher(out);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }

    /** 解析 "connected to \"SSID\"" / 'connected to SSID' */
    private static String parseConnectedSsid(String out) {
        if (out == null) return null;
        Matcher m = Pattern.compile("connected\\s+to\\s+[\"']?([^\"'\\n]+)[\"']?", Pattern.CASE_INSENSITIVE)
                .matcher(out);
        if (m.find()) return m.group(1).trim();
        return null;
    }
}
