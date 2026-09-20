package com.luminaauth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启 / 应用更新恢复接收器：
 * - 设备开机完成后自动拉起自动认证前台服务（无 root / Shizuku 的普通环境下，
 *   这是系统回收进程后最重要的恢复手段之一）
 * - 应用升级（MY_PACKAGE_REPLACED）后服务被系统停止时同样自动恢复
 * 仅在用户已开启自动认证开关时生效。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        // 自动认证开关关闭时不做任何事（服务由用户在界面手动启停）
        if (!PrefUtils.isAutoAuthEnabled(context)) return;
        try {
            Intent svc = new Intent(context, AutoLoginService.class);
            svc.setPackage(context.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            LogBuffer.add("服务", "开机自启：已拉起自动认证前台服务");
        } catch (Exception e) {
            LogBuffer.add("服务", "开机自启失败: " + e.getMessage());
        }
    }
}
