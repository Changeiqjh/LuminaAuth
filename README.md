# LuminaAuth

校园网自动登录 Android 应用，支持连接指定校园网 WiFi 后自动完成认证登录。

## 功能特性

- 🔐 **自动认证**：连接到目标校园网 WiFi 后自动完成登录认证
- 📶 **目标 SSID**：ChinaNet-JLZG / -5G / -2.4G、JLZG / -5G / -2.4G
- 📡 **通知检测模式**：监听系统「登录到WLAN网络」等网关通知，捕获信号立即触发认证（无需依赖 WiFi 广播）
- ⚡ **增强模式**：支持 Root / Shizuku 提权，后台可靠检测 WiFi 状态
- 🔔 **前台服务**：常驻通知实时显示认证状态，单条通知更新
- 🎨 **液态玻璃 UI**：基于 miuix-kmp + AndroidLiquidGlass，SukiSU 同款视觉
- 📱 **Dock 导航**：三页液态玻璃底栏（主页 / 日志 / 设置）
- ⚙️ **可调检测速度**：0.5s - 10s 全局轮询间隔
- 🔑 **记住密码**：用户名密码本地持久化存储

## 通知监听自动恢复

国产 ROM（MIUI / ColorOS / OriginOS）在进程被杀或重启后，可能断开 `NotificationListenerService` 的系统绑定，导致收不到网关通知。应用内置三级自动恢复策略：

1. **官方 API `requestBindListener`**（反射调用，部分 ROM 有效）
2. **组件开关 toggle**（`COMPONENT_ENABLED_STATE_DISABLED -> ENABLED`，无需任何权限，全 ROM 通用）
3. **root 强绑定**（增强模式 + root 时执行 `cmd notification allow_listener`）

触发时机：

- **每次启动自动重绑**：通知检测模式开启 + 已获得通知使用权 + 监听未连接时，应用启动后自动请求重绑（延迟 1.2s）
- **设置页监听状态轮询**：每 3 秒检测监听连接状态，断开时自动触发重绑
- **前台服务守护**：服务运行中发现监听断开，自动触发重绑
- 重绑流程带 30s 冷却 + 全局互斥，避免高频触发

## 技术栈

- **UI**：Jetpack Compose + miuix-kmp 0.9.3
- **液态玻璃**：AndroidLiquidGlass (Kyant0)
- **最低系统**：Android 10 (API 29)
- **目标系统**：Android 16 (API 36)
- **编译 SDK**：Android 17 (API 37)
- **构建工具**：Gradle 9.5.0 + AGP 9.3.2 + Kotlin 2.4.10

## 开源协议

本项目代码采用 **GNU GPL-3.0-or-later** 协议开源。

### 第三方依赖

| 项目 | 协议 | 作者 |
|---|---|---|
| AndroidLiquidGlass | Apache-2.0 | Kyant0 |
| miuix-kmp | GPL-3.0 | Yukonga |
| Shizuku | MIT | Rikka |

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
```

签名密钥：`schoolautologin-release.keystore`（alias=schoolautologin）

## 版本

- versionName: 1.1.0
- versionCode: 114
- 包名: com.luminaauth
