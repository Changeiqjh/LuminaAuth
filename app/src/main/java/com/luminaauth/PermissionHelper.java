package com.luminaauth;

import android.app.Activity;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionHelper {
    private static final String[] REQUIRED_PERMISSIONS = {
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            // 如需存储权限可取消注释：
            // android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };
    private static final int REQUEST_CODE = 1001;

    private static int currentIndex = 0;
    private static OnPermissionCallback callback;

    public interface OnPermissionCallback {
        void onAllGranted();
        void onDenied(String permission);
    }

    public static void start(Activity activity, OnPermissionCallback cb) {
        callback = cb;
        currentIndex = 0;
        requestNext(activity);
    }

    private static void requestNext(Activity activity) {
        if (currentIndex >= REQUIRED_PERMISSIONS.length) {
            if (callback != null) callback.onAllGranted();
            return;
        }
        String permission = REQUIRED_PERMISSIONS[currentIndex];
        if (ContextCompat.checkSelfPermission(activity, permission)
                == PackageManager.PERMISSION_GRANTED) {
            currentIndex++;
            requestNext(activity);
        } else {
            ActivityCompat.requestPermissions(activity,
                    new String[]{permission}, REQUEST_CODE);
        }
    }

    public static void onRequestPermissionsResult(Activity activity,
                                                  int requestCode,
                                                  String[] permissions,
                                                  int[] grantResults) {
        if (requestCode == REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                currentIndex++;
                requestNext(activity);
            } else {
                if (callback != null) {
                    callback.onDenied(permissions.length > 0 ? permissions[0] : "unknown");
                }
            }
        }
    }
}
