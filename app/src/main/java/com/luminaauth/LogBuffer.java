package com.luminaauth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 内存日志缓冲：
 * - 全局单例，供服务 / 广播接收器 / 页面共同写入
 * - 日志保存在内存，App 进程结束后清空
 * - 上限 500 条，超出丢弃最旧
 */
public class LogBuffer {
    private static final int MAX_ENTRIES = 500;
    private static final List<String> logs = new ArrayList<>();
    private static LogListener listener;

    /** 日志变化回调（Dock 日志页刷新用） */
    public interface LogListener {
        void onLogUpdated();
    }

    public static void setListener(LogListener l) {
        listener = l;
    }

    public static synchronized void add(String tag, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add("[" + time + "] [" + tag + "] " + message);
        if (logs.size() > MAX_ENTRIES) {
            logs.remove(0);
        }
        if (listener != null) {
            listener.onLogUpdated();
        }
    }

    /** 获取全部日志文本 */
    public static synchronized String getAllText() {
        StringBuilder sb = new StringBuilder();
        for (String line : logs) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 清空日志 */
    public static synchronized void clear() {
        logs.clear();
        if (listener != null) {
            listener.onLogUpdated();
        }
    }
}
