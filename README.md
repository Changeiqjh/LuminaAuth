# LuminaAuth

校园网自动登录 Android 应用，支持连接指定校园网 WiFi 后自动完成认证登录。

## 功能特性

- 🔐 **自动认证**：连接到目标校园网 WiFi 后自动完成登录认证
- 📶 **目标 SSID**：ChinaNet-JLZG / -5G / -2.4G、JLZG / -5G / -2.4G
- ⚡ **增强模式**：支持 Root / Shizuku 提权，后台可靠检测 WiFi 状态
- 🔔 **前台服务**：常驻通知实时显示认证状态，单条通知更新
- 🎨 **液态玻璃 UI**：基于 miuix-kmp + AndroidLiquidGlass，SukiSU 同款视觉
- 📱 **Dock 导航**：三页液态玻璃底栏（主页 / 日志 / 设置）
- ⚙️ **可调检测速度**：0.5s - 10s 全局轮询间隔
- 🔑 **记住密码**：用户名密码本地持久化存储

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

- versionName: 1.0.2
- versionCode: 102
- 包名: com.luminaauth
