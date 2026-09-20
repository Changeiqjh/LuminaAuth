package com.luminaauth;

import android.content.Context;

/**
 * 全局 Context 单例，供静态方法使用。
 * 在 MainActivity.onCreate 中初始化。
 */
public class AppContext {
    private static Context context;

    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
    }

    public static Context get() {
        return context;
    }
}
