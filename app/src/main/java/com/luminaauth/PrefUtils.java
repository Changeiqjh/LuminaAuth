package com.luminaauth;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefUtils {
    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_OOBE_COMPLETED = "oobe_completed";
    private static final String KEY_AUTO_AUTH = "auto_auth";
    private static final String KEY_POLL_INTERVAL = "poll_interval_ms";
    private static final String KEY_DOCK_ENABLED = "dock_enabled";
    private static final String KEY_ENHANCED_MODE = "enhanced_mode";
    private static final String KEY_REMEMBER_PWD = "remember_pwd";

    /** 检测间隔默认值（毫秒）：10 秒 */
    public static final long DEFAULT_POLL_INTERVAL_MS = 10000L;

    public static long getPollIntervalMs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL_MS);
    }

    /** 液态玻璃 Dock 开关（默认开启） */
    public static boolean isDockEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DOCK_ENABLED, true);
    }

    public static void setDockEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DOCK_ENABLED, enabled)
                .apply();
    }

    public static void setPollIntervalMs(Context context, long intervalMs) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_POLL_INTERVAL, intervalMs)
                .apply();
    }

    public static boolean isAutoAuthEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_AUTH, false);
    }

    public static void setAutoAuthEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_AUTH, enabled)
                .apply();
    }

    /** 增强模式（需 root）：用 root 权限检查目标 WiFi，默认关闭 */
    public static boolean isEnhancedModeEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENHANCED_MODE, false);
    }

    public static void setEnhancedModeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENHANCED_MODE, enabled)
                .apply();
    }

    /** 记住密码开关状态 */
    public static boolean isRememberPwdEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_REMEMBER_PWD, false);
    }

    public static void setRememberPwdEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REMEMBER_PWD, enabled)
                .apply();
    }

    /** 清除已保存的用户名密码（取消记住密码时调用） */
    public static void clearConfig(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove("username")
                .remove("password")
                .remove("isp")
                .apply();
    }

    public static boolean isOobeCompleted(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_OOBE_COMPLETED, false);
    }

    public static void setOobeCompleted(Context context, boolean completed) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_OOBE_COMPLETED, completed)
                .apply();
    }

    public static void saveConfig(Context context, String username, String password, String isp) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("username", username)
                .putString("password", password)
                .putString("isp", isp)
                .apply();
    }

    public static String[] loadConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String u = prefs.getString("username", null);
        String p = prefs.getString("password", null);
        String i = prefs.getString("isp", null);
        if (u == null || p == null || i == null) return null;
        return new String[]{u, p, i};
    }
}
