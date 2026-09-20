package com.luminaauth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;

/**
 * WiFi 广播转发器（纯唤醒作用）：
 * 收到 WiFi / 连接性变化广播后只负责唤醒前台服务 AutoLoginService，
 * 所有检测与自动认证逻辑统一由前台服务内的状态机执行。
 * - 服务运行期间由服务内部动态广播负责即时触发，本接收器作为"能收到时"的兜底唤醒；
 * - Android 8+ 后台对隐式 WiFi 广播有限制，开机自启 + START_STICKY 是主恢复路径。
 */
public class AutoLoginReceiver extends BroadcastReceiver {

    /** 唤醒前台服务立即执行一轮状态机检查（静态广播入口使用） */
    public static final String ACTION_TRIGGER_CHECK = "com.luminaauth.TRIGGER_CHECK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        boolean networkChanged = WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action);
        boolean suppChanged = WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION.equals(action);
        boolean connectivityChanged = "android.net.conn.CONNECTIVITY_CHANGE".equals(action);
        if (!networkChanged && !suppChanged && !connectivityChanged) return;
        // 只唤醒前台服务，绝不在广播接收器的短生命周期里做任何检测 / 网络 IO / 登录
        if (!PrefUtils.isAutoAuthEnabled(context)) return;
        try {
            Intent svc = new Intent(context, AutoLoginService.class);
            svc.setAction(ACTION_TRIGGER_CHECK);
            svc.setPackage(context.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {}
    }
}
