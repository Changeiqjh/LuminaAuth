package com.luminaauth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * 常驻前台服务：
 * 保证后台自动登录可靠执行。
 * - 动态广播监听 WiFi 变化，立即触发自动认证
 * - 定时轮询兜底：即使广播未触发，也周期性检查校园网并自动认证
 * - 同一条常驻通知实时显示自动执行状态（检测到 WiFi / 等待就绪 / 认证中 / 成功 / 失败）
 */
public class AutoLoginService extends Service {

    private static final String CHANNEL_ID = "auto_login_service";
    private static final int NOTIFY_ID = 2001;

    /** 当前服务实例，供 restartPolling 调用 */
    private static AutoLoginService instance;

    /** 后台轮询线程（增强模式下 root 命令不阻塞主线程） */
    private android.os.HandlerThread bgThread;
    private Handler handler;

    /** 动态广播：WiFi 状态 / 连接性变化时立即触发 */
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AutoLoginReceiver.performAutoLogin(context);
        }
    };

    /** 定时轮询：周期性检查是否在校园网，自动触发认证 */
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (PrefUtils.isAutoAuthEnabled(AutoLoginService.this)) {
                    String ssid = AutoLoginReceiver.getCurrentSsid(AutoLoginService.this);
                    if (AutoLoginReceiver.isTargetSsid(ssid)) {
                        AutoLoginReceiver.performAutoLogin(AutoLoginService.this);
                    }
                }
            } catch (Exception ignored) {}
            // 每次重新读取用户设置的检测间隔（滑块调节后即时生效）
            long interval = PrefUtils.getPollIntervalMs(AutoLoginService.this);
            handler.postDelayed(this, interval);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 后台轮询线程：增强模式下 root 命令（su）不会阻塞主线程
        bgThread = new android.os.HandlerThread("autologin-poll");
        bgThread.start();
        handler = new Handler(bgThread.getLooper());
        LogBuffer.add("服务", "前台服务已启动（后台监测校园网）");
        startForeground(NOTIFY_ID, buildNotification("校园网自动认证", "后台监听校园网 WiFi，自动完成登录"));
        registerNetworkReceiver();
        handler.post(pollTask);
        // 启动后立即检查一次
        AutoLoginReceiver.performAutoLogin(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        LogBuffer.add("服务", "前台服务已停止");
        try {
            unregisterReceiver(networkReceiver);
        } catch (Exception ignored) {}
        if (handler != null) handler.removeCallbacks(pollTask);
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        super.onDestroy();
    }

    /** 让服务立即按新的检测间隔重新调度轮询（滑块调节时调用） */
    public static void restartPolling(Context context) {
        AutoLoginService s = instance;
        if (s != null && s.handler != null) {
            s.handler.removeCallbacks(s.pollTask);
            s.handler.post(s.pollTask);
        }
    }

    /** 更新同一条常驻通知的状态内容（各阶段实时反馈） */
    public static void updateStatus(Context context, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "校园网自动认证服务",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .setShowWhen(false)
                    // 同一通知后续只改内容，不重复响铃/震动
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "校园网自动认证服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void registerNetworkReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(networkReceiver, filter);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
