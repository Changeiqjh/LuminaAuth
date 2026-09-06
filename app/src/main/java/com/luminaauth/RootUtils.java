package com.luminaauth;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root 权限工具类：检测 root 并以 root 权限执行命令。
 * 兼容 Magisk / KernelSU / SukiSU / Apatch 等主流 root 方案。
 */
public class RootUtils {

    private static Boolean rootAvailable = null;
    private static final Object lock = new Object();

    /** 检测是否有 root 权限（缓存结果，带超时） */
    public static boolean isRootAvailable() {
        synchronized (lock) {
            if (rootAvailable != null) return rootAvailable;
            rootAvailable = checkRoot();
            return rootAvailable;
        }
    }

    /** 清除缓存（root 状态变化时调用） */
    public static void resetCache() {
        synchronized (lock) {
            rootAvailable = null;
        }
    }

    private static boolean checkRoot() {
        // 方式1：su -c id 直接执行（最可靠，兼容 SukiSU/Magisk/KSU）
        String output = execCommand("su -c id", 5000);
        if (output != null && output.contains("uid=0")) return true;

        // 方式2：交互式 su + id
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.writeBytes("exit\n");
            os.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                if (System.currentTimeMillis() - start > 5000) break;
            }
            process.waitFor();
            process.destroy();
            if (sb.toString().contains("uid=0")) return true;
        } catch (Throwable ignored) {}

        // 方式3：检查常见 su 路径（有 su 二进制但可能未授权）
        String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
                "/su/bin/su", "/debug_ramdisk/su", "/apex/com.android.adbd/bin/su"};
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }

        // 方式4：which su
        String which = execCommand("which su", 3000);
        if (which != null && !which.isEmpty()) return true;

        return false;
    }

    /** 执行命令并返回 stdout，带超时（毫秒） */
    private static String execCommand(String command, int timeoutMs) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            final StringBuilder sb = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
            readerThread.start();
            boolean finished = waitForProcess(process, timeoutMs);
            if (!finished) {
                process.destroy();
            }
            readerThread.join(1000);
            return sb.toString().trim();
        } catch (Throwable t) {
            if (process != null) process.destroy();
            return null;
        }
    }

    private static boolean waitForProcess(Process process, int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    /** 以 root 权限执行命令，返回 stdout（失败返回 null） */
    public static String exec(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("echo \"__DONE__\"\n");
            os.writeBytes("exit\n");
            os.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                if ("__DONE__".equals(line.trim())) break;
                sb.append(line).append("\n");
                if (System.currentTimeMillis() - start > 10000) break;
            }
            waitForProcess(process, 10000);
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** 用 root 读取当前 WiFi SSID */
    public static String getWifiSsidViaRoot() {
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

    private static String parseConnectedSsid(String out) {
        if (out == null) return null;
        Matcher m = Pattern.compile("connected\\s+to\\s+[\"']?([^\"'\\n]+)[\"']?", Pattern.CASE_INSENSITIVE)
                .matcher(out);
        if (m.find()) return m.group(1).trim();
        return null;
    }
}
