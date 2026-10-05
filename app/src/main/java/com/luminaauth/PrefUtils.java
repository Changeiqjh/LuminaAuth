package com.luminaauth;

import android.content.Context;
import android.content.SharedPreferences;
import com.luminaauth.plugin.PluginManager;

public class PrefUtils {
    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_AUTO_AUTH = "auto_auth";
    private static final String KEY_POLL_INTERVAL = "poll_interval_ms";
    private static final String KEY_WAIT_TIME = "wait_time_ms";
    private static final String KEY_ENHANCED_MODE = "enhanced_mode";
    private static final String KEY_SSID_LIST = "ssid_list";
    private static final String KEY_LOGIN_URL = "login_url";
    private static final String KEY_REMEMBER_PWD = "remember_pwd";

    /** 默认目标 SSID 列表由当前活动插件提供（network.ssid）。 */
    private static String pluginDefaultSsidList(Context context) {
        return String.join(",", PluginManager.INSTANCE.defaultSsids(context));
    }

    /** 默认登录地址由当前活动插件的 login 步骤 URL 派生（去掉查询串）。 */
    private static String pluginDefaultLoginUrl(Context context) {
        return PluginManager.INSTANCE.defaultLoginUrl(context);
    }

    /** 检测间隔默认值（毫秒）：10 秒 */
    public static final long DEFAULT_POLL_INTERVAL_MS = 10000L;

    /** 网络就绪后、认证前的稳定等待默认值（毫秒）：5 秒 */
    public static final long DEFAULT_WAIT_TIME_MS = 5000L;

    public static long getPollIntervalMs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL_MS);
    }

    public static void setPollIntervalMs(Context context, long intervalMs) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_POLL_INTERVAL, intervalMs)
                .apply();
    }

    /** 网络就绪后、认证前的稳定等待时间（毫秒） */
    public static long getWaitTimeMs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_WAIT_TIME, DEFAULT_WAIT_TIME_MS);
    }

    public static void setWaitTimeMs(Context context, long waitMs) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_WAIT_TIME, waitMs)
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

    // ==================== 通知检测模式（增强模式下可选） ====================

    private static final String KEY_NOTIFY_DETECT = "notify_detect";

    /** 通知检测模式：监听校园网关认证/下线通知，捕获到立即触发会话探测，默认关闭 */
    public static boolean isNotifyDetectEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFY_DETECT, false);
    }

    public static void setNotifyDetectEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTIFY_DETECT, enabled)
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

    /** 获取目标 SSID 列表（逗号分隔）；未自定义时返回活动插件声明的默认列表。 */
    public static String getSsidList(Context context) {
        String persisted = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SSID_LIST, null);
        // A non-blank persisted list is a real customization; blank/empty falls
        // back to the active plugin's defaults.
        if (persisted != null && !persisted.trim().isEmpty()) return persisted;
        String def = pluginDefaultSsidList(context);
        return def == null ? "" : def;
    }

    /** 获取目标 SSID 数组 */
    public static String[] getSsidArray(Context context) {
        String list = getSsidList(context);
        if (list == null || list.trim().isEmpty()) return new String[0];
        return list.split(",");
    }

    /** 保存目标 SSID 列表 */
    public static void setSsidList(Context context, String ssidList) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SSID_LIST, ssidList)
                .apply();
    }

    /** 恢复默认 SSID 列表：清除自定义，回到活动插件声明的默认列表。 */
    public static void resetSsidList(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_SSID_LIST).apply();
    }

    /** 添加一个 SSID */
    public static void addSsid(Context context, String ssid) {
        if (ssid == null || ssid.trim().isEmpty()) return;
        String current = getSsidList(context);
        String[] arr = current.split(",");
        for (String s : arr) {
            if (s.trim().equalsIgnoreCase(ssid.trim())) return; // 已存在
        }
        String newList = current.isEmpty() ? ssid.trim() : current + "," + ssid.trim();
        setSsidList(context, newList);
    }

    /** 删除一个 SSID */
    public static void removeSsid(Context context, String ssid) {
        if (ssid == null || ssid.trim().isEmpty()) return;
        String current = getSsidList(context);
        String[] arr = current.split(",");
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (!s.trim().equalsIgnoreCase(ssid.trim())) {
                if (sb.length() > 0) sb.append(",");
                sb.append(s.trim());
            }
        }
        setSsidList(context, sb.toString());
    }

    /** 获取登录地址；未自定义时返回活动插件派生的默认登录地址。 */
    public static String getLoginUrl(Context context) {
        String url = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LOGIN_URL, null);
        // 自动修复：旧的网页地址（/a79.htm）回退到插件提供的默认登录 API
        if (url != null && url.contains("/a79.htm")) {
            resetLoginUrl(context);
            return pluginDefaultLoginUrl(context);
        }
        if (url != null && !url.trim().isEmpty()) return url;
        return pluginDefaultLoginUrl(context);
    }

    /** 保存登录地址 */
    public static void setLoginUrl(Context context, String url) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LOGIN_URL, url)
                .apply();
    }

    /** 恢复默认登录地址：清除自定义，回到活动插件派生的默认地址。 */
    public static void resetLoginUrl(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_LOGIN_URL).apply();
    }

    public static void saveConfig(Context context, String username, String password, String ispSuffix) {
        // ispSuffix 为账号后缀：本部署固定使用教职工(after)，UI 已不再提供运营商选择
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("username", username)
                .putString("password", password)
                .putString("isp", ispSuffix == null ? "" : ispSuffix)
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

    // 主题模式：0=跟随系统，1=浅色，2=深色
    private static final String KEY_THEME_MODE = "theme_mode";

    public static int getThemeMode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_THEME_MODE, 0);
    }

    public static void setThemeMode(Context context, int mode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_THEME_MODE, mode)
                .apply();
    }

    // ==================== 自定义调色板（ARGB，以 Long 存储） ====================

    /** 调色板 key 统一前缀，便于整体重置与变更监听 */
    public static final String PALETTE_KEY_PREFIX = "palette_";

    public static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 读取某个配色项的 ARGB 值，未自定义时返回默认值 defArgb */
    public static long getPaletteColor(Context context, String key, long defArgb) {
        return prefs(context).getLong(key, defArgb);
    }

    /** 保存某个配色项的 ARGB 值 */
    public static void setPaletteColor(Context context, String key, long argb) {
        prefs(context).edit().putLong(key, argb).apply();
    }

    /** 重置某个配色项（清除自定义，恢复默认） */
    public static void resetPaletteColor(Context context, String key) {
        prefs(context).edit().remove(key).apply();
    }

    /** 一键重置全部自定义配色（清除所有 palette_ 开头的项） */
    public static void resetAllPaletteColors(Context context) {
        android.content.SharedPreferences sp = prefs(context);
        android.content.SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key != null && key.startsWith(PALETTE_KEY_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    // ==================== 认证状态机持久化 ====================

    private static final String KEY_AUTH_STATE = "auth_status";
    private static final String KEY_AUTH_STATE_TIME = "auth_status_ts";
    private static final String KEY_WIFI_DISCONNECT_TS = "wifi_disconnect_ts";

    /** 保存认证状态（ordinal）与时间戳 */
    public static void saveAuthState(Context context, int statusOrdinal, long timestamp) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_AUTH_STATE, statusOrdinal)
                .putLong(KEY_AUTH_STATE_TIME, timestamp)
                .apply();
    }

    /** 读取持久化认证状态，无记录返回 -1 */
    public static int loadAuthState(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_AUTH_STATE, -1);
    }

    /** 读取认证状态保存时间戳，无记录返回 0 */
    public static long getAuthStateTime(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_AUTH_STATE_TIME, 0L);
    }

    /** 记录 WiFi 断开时间戳（闪断防抖用） */
    public static void saveWifiDisconnectTs(Context context, long ts) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_WIFI_DISCONNECT_TS, ts)
                .apply();
    }

    /** 读取 WiFi 断开时间戳，无记录返回 0 */
    public static long getWifiDisconnectTs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_WIFI_DISCONNECT_TS, 0L);
    }
}