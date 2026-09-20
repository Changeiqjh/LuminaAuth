package com.luminaauth;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService：由 Shizuku fork 到 shell 权限进程运行。
 * 通过 exec() 在 shell uid 下执行系统命令（cmd wifi status / dumpsys wifi 等）。
 */
public class ShellUserService extends Service {

    private final IShell.Stub binder = new IShell.Stub() {
        @Override
        public String exec(String command) throws RemoteException {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    return null;
                }
                return readAll(process.getInputStream());
            } catch (Throwable t) {
                return null;
            } finally {
                if (process != null) {
                    try { process.destroy(); } catch (Throwable ignored) {}
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        byte[] buf = new byte[8192];
        int total = 0;
        StringBuilder sb = new StringBuilder();
        int len;
        while ((len = is.read(buf)) > 0) {
            sb.append(new String(buf, 0, len));
            total += len;
            if (total > 65536) break;
        }
        return sb.toString();
    }
}
