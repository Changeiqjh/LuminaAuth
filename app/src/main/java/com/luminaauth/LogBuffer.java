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
 * - 普通日志上限 300 条，超出丢弃最旧
 * - 详细日志（全局详细日志区）上限 1000 条，超出丢弃最旧
 * - 单条最长 512 字符，超长截断（防止误记超长输出撑爆内存）
 *   最坏占用约 300 × 0.5KB ≈ 150KB，正常场景仅几 KB
 */
public class LogBuffer {
    private static final int MAX_ENTRIES = 300;
    private static final int DETAIL_MAX_ENTRIES = 1000;
    private static final int MAX_LINE_LENGTH = 512;
    private static final List<String> logs = new ArrayList<>();
    /** 全局详细日志：服务端状态机 / 探测 / 防抖 / 网络等底层细节，原样保留 */
    private static final List<String> detailLogs = new ArrayList<>();
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
        String line = "[" + time + "] [" + tag + "] " + message;
        // 截断超长单条日志，避免一次性写入过大字符串
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH) + "…";
        }
        logs.add(line);
        if (logs.size() > MAX_ENTRIES) {
            logs.remove(0);
        }
        if (listener != null) {
            listener.onLogUpdated();
        }
    }

    /**
     * 写入全局详细日志（LogPage 下半部分展示）。
     * 与普通日志共用同一套时间格式与监听回调；上限 1000 条，超出丢弃最旧。
     */
    public static synchronized void addDetail(String tag, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + time + "] [" + tag + "] " + message;
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH) + "…";
        }
        detailLogs.add(line);
        if (detailLogs.size() > DETAIL_MAX_ENTRIES) {
            detailLogs.remove(0);
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

    /** 获取全局详细日志全部文本 */
    public static synchronized String getDetailAllText() {
        StringBuilder sb = new StringBuilder();
        for (String line : detailLogs) {
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

    /** 清空全局详细日志 */
    public static synchronized void clearDetail() {
        detailLogs.clear();
        if (listener != null) {
            listener.onLogUpdated();
        }
    }
}
