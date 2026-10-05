package com.luminaauth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Machine-oriented in-memory ring buffer (compact ASCII, AI/parser friendly).
 *
 * Line schema:  HH:mm:ss.SSS TAG event [k=v ...]
 * - run log  -> add(..)        cap 300
 * - detail   -> addDetail(..)  cap 1000
 *
 * Rules: no human prose, no CJK, booleans as 1/0, free-text fields last.
 * Storage is process-scoped only; everything is dropped on process death.
 */
public class LogBuffer {
    private static final int MAX_ENTRIES = 300;
    private static final int DETAIL_MAX_ENTRIES = 1000;
    private static final int MAX_LINE_LENGTH = 512;
    private static final List<String> logs = new ArrayList<>();
    private static final List<String> detailLogs = new ArrayList<>();
    private static LogListener listener;

    public interface LogListener {
        void onLogUpdated();
    }

    public static void setListener(LogListener l) {
        listener = l;
    }

    private static String format(String tag, String message) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = ts + " " + tag + " " + message;
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH);
        }
        return line;
    }

    public static synchronized void add(String tag, String message) {
        logs.add(format(tag, message));
        if (logs.size() > MAX_ENTRIES) {
            logs.remove(0);
        }
        if (listener != null) {
            listener.onLogUpdated();
        }
    }

    public static synchronized void addDetail(String tag, String message) {
        detailLogs.add(format(tag, message));
        if (detailLogs.size() > DETAIL_MAX_ENTRIES) {
            detailLogs.remove(0);
        }
        if (listener != null) {
            listener.onLogUpdated();
        }
    }

    public static synchronized String getAllText() {
        StringBuilder sb = new StringBuilder();
        for (String line : logs) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String getDetailAllText() {
        StringBuilder sb = new StringBuilder();
        for (String line : detailLogs) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        logs.clear();
        if (listener != null) {
            listener.onLogUpdated();
        }
    }

    public static synchronized void clearDetail() {
        detailLogs.clear();
        if (listener != null) {
            listener.onLogUpdated();
        }
    }
}
