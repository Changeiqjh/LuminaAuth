package com.luminaauth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root 权限工具类：
 * - 维护一个常驻 root shell（su）：启动时只申请一次授权，之后所有命令复用同一个 shell，
 *   不再重复弹 root 授权框（"root 握在手里"）
 * - 兼容 Magisk / KernelSU / SukiSU / Apatch 等主流 root 方案
 * - shell 被系统回收 / 异常退出时自动重建（此时才会重新申请一次授权）
 * - exec() 串行执行，同一时刻只有一条命令在 shell 中运行，线程安全
 */
public class RootUtils {

    private static final Object SHELL_LOCK = new Object();
    private static final Object OUT_LOCK = new Object();
    private static final StringBuilder outBuf = new StringBuilder();
    private static Process shellProcess = null;
    private static BufferedWriter shellIn = null;
    private static int seq = 0;

    /**
     * 确保常驻 root shell 存活。
     * 首次调用会触发一次 root 授权（su），此后复用；shell 失效时自动重建。
     */
    private static Process ensureShell() {
        synchronized (SHELL_LOCK) {
            if (shellProcess != null && shellProcess.isAlive() && shellIn != null) {
                return shellProcess;
            }
            closeShell();
            try {
                Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
                // 常驻读线程：持续把 shell 输出写入缓冲，防止管道写满阻塞
                Thread reader = new Thread(() -> {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (OUT_LOCK) {
                                outBuf.append(line).append('\n');
                                OUT_LOCK.notifyAll();
                            }
                        }
                    } catch (Exception ignored) {}
                }, "root-shell-reader");
                reader.setDaemon(true);
                reader.start();
                shellProcess = p;
                shellIn = writer;
                // 等待 shell 就绪（su 授权 / 快速响应）
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                return p;
            } catch (Throwable t) {
                closeShell();
                return null;
            }
        }
    }

    private static void closeShell() {
        try {
            if (shellIn != null) shellIn.close();
        } catch (Exception ignored) {}
        try {
            if (shellProcess != null) shellProcess.destroy();
        } catch (Exception ignored) {}
        shellProcess = null;
        shellIn = null;
    }

    /** 是否有 root（确保常驻 shell 存活即视为已握有 root） */
    public static boolean isRootAvailable() {
        return ensureShell() != null;
    }

    /** 清除缓存：关闭常驻 shell（下次使用时重新申请一次授权） */
    public static void resetCache() {
        closeShell();
    }

    /**
     * 通过常驻 root shell 执行命令，返回 stdout（失败返回 null）。
     * 串行执行；shell 异常时自动重建并重试一次。
     */
    public static String exec(String command) {
        synchronized (SHELL_LOCK) {
            Process p = ensureShell();
            if (p == null || shellIn == null) return null;
            String marker = "__LUMINA_" + (++seq) + "__";
            synchronized (OUT_LOCK) {
                outBuf.setLength(0);
            }
            try {
                shellIn.write(command + "\n");
                shellIn.write("echo " + marker + " $?\n");
                shellIn.flush();
            } catch (Exception e) {
                closeShell();
                return null;
            }
            StringBuilder result = new StringBuilder();
            long deadline = System.currentTimeMillis() + 10000;
            boolean done = false;
            synchronized (OUT_LOCK) {
                while (System.currentTimeMillis() < deadline) {
                    String buf = outBuf.toString();
                    int idx = buf.indexOf(marker);
                    if (idx >= 0) {
                        result.append(buf, 0, idx);
                        outBuf.delete(0, idx + marker.length());
                        done = true;
                        break;
                    }
                    try {
                        long remain = deadline - System.currentTimeMillis();
                        if (remain <= 0) break;
                        OUT_LOCK.wait(Math.min(1000, remain));
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (!done) {
                // 超时未收到结束标记：shell 可能已卡死，重建
                closeShell();
                return null;
            }
            String r = result.toString().trim();
            return r.isEmpty() ? null : r;
        }
    }

    /**
     * 增强模式：把当前进程优先级拉到最高（需 root）。
     * - oom_score_adj 写入 -17：进程被系统视为"不可见"级别，内存紧张回收时几乎不会被选中
     * - nice 值拉到 -20：CPU 调度优先级最高
     * 返回是否成功（无 root / su 拒绝时返回 false）。
     */
    public static boolean boostProcessPriority() {
        try {
            int pid = android.os.Process.myPid();
            exec("echo -17 > /proc/" + pid + "/oom_score_adj");
            exec("renice -n -20 -p " + pid);
            return true;
        } catch (Throwable t) {
            return false;
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
