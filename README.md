# LuminaAuth

![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue)
![Platform](https://img.shields.io/badge/Android-10%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7f52ff)

校园网自动登录 Android 应用。连接到目标校园网 WiFi 后，自动完成 Web 认证（DrCOM / 锐捷 / 深澜 / H3C 等 Portal 页面），无需每次手动打开浏览器输入账号密码。

不同学校、不同运营商线路的认证差异全部通过 **YAML 插件** 描述：插件只是纯数据配置，不包含可执行代码，由应用内置的运行时在网络沙箱内发起请求，安全可控。

## 功能特性

- 🔐 **自动认证**：连接匹配的校园网 WiFi 后自动完成登录，断线自动重连
- 🧩 **YAML 插件体系**：认证地址、表单字段、运营商线路、SSID、成功判定全部声明式配置，无需重新编译
- 📶 **SSID 自动匹配**：连接到插件声明的 WiFi 才触发，也可设为任意网络
- 🛗 **ISP 运营商分段选择**：学生 / 教职工等多账号类型，液态玻璃分段组件一键切换（仅 1 个选项时自动隐藏）
- 📡 **通知检测模式**：监听系统「登录到 WLAN 网络」等网关通知，捕获即触发认证，不依赖 WiFi 广播
- 🦸 **增强模式**：支持 Root / Shizuku 提权，后台可靠检测网络状态、提升进程优先级
- 🔔 **前台服务守护**：常驻通知实时显示认证状态，单条通知增量更新
- 🎨 **液态玻璃 UI**：基于 Jetpack Compose + miuix-kmp + AndroidLiquidGlass，部分交互思路参考 SukiSU Ultra
- 🌈 **主题与配色**：主题色 / 状态色 / 玻璃元素各自独立调色板，可逐项自定义并持久化
- 🚀 **高刷流畅渲染**：解锁高刷新率，列表按行懒加载，翻页 / 滑动稳定跟手
- 🔑 **记住密码**：账号凭据本地加密持久化存储

## 截图

<!-- 将截图放入 docs/ 目录后在此引用，例如：
![主页](docs/home.png)
![日志](docs/log.png)
![设置](docs/settings.png)
-->

## 下载

前往 [Releases](https://github.com/Changeiqjh/LuminaAuth/releases) 下载最新的 `LuminaAuth-*-release.apk` 安装即可。应用已使用固定 release 证书签名，可直接覆盖升级。

## 使用方法

1. 安装并打开应用，授予定位、通知、电池优化等必要权限（用于识别 WiFi 名称与后台保活）。
2. 内置插件已包含默认校园网配置；如需适配其他学校，导入对应的 `.plugin.yaml` 插件文件。
3. 输入校园网账号密码，可选择「记住密码」。
4. 开启「自动认证」，之后连接校园网 WiFi 即会自动登录。

## YAML 插件体系

插件为**纯数据、零代码**：加载插件不会执行任何脚本，所有请求由内置运行时 `PluginRuntime` 处理，并被限制在插件声明的主机白名单（沙箱）内。

### 顶层结构

| 字段 | 说明 |
|---|---|
| `id` | 插件唯一标识 |
| `name` / `description` | 显示名称与描述 |
| `version` | 插件版本号（整数） |
| `network.ssid` | 触发认证的 WiFi 名称列表（子串匹配）；留空表示任意网络 |
| `sandbox.hosts` | **沙箱白名单**，所有请求只能发往这些 `host:port`，端口必须与实际请求一致 |
| `fields` | 登录表单字段，动态渲染输入框；`type` 为 `text` / `password` |
| `isps` | 运营商账号类型列表；`default: true` 为默认选中，≥2 项才显示选择器 |
| `check` | 在线状态探测：`online_when` / `offline_when` 用 `contains` 判定 |
| `login.steps` | 登录请求步骤，支持多步串行与变量捕获 |

### 模板变量

可在任意请求 URL 中使用 `${...}` 占位符，运行时自动替换：

| 变量 | 含义 |
|---|---|
| `${username}` / `${password}` | 用户在表单中输入的内容（也可用任意自定义 field 的 `id`） |
| `${isp}` | 当前选中的 ISP 的 `id`（运营商后缀） |
| `${ip}` | 本机局域网 IP |
| `${rand}` | 每次运行生成的随机数（1000–9999） |
| 上一步 `capture` 的变量 | 从前序响应中按 JSONPath / 正则提取，供后续步骤使用 |

### 步骤结果判定

- `success_when`：`contains`（包含文本）/ `regex`（正则）/ `json.path` + `json.equals`（JSONPath 比对）
- `already_online_when`：已在线判定
- `expect_status`：期望的 HTTP 状态码
- `capture`：从响应中提取变量（`json_path` 或 `regex` + `group`）
- `fail.json_path`：失败时从该 JSONPath 读取服务器错误提示

### 完整示例

```yaml
id: drcom-example
name: DrCOM 校园网认证
version: 1
description: DrCOM ePortal Web 认证示例

network:
  ssid:
    - Campus-WiFi

sandbox:
  hosts:
    - 192.168.1.100:80

fields:
  - id: username
    name: 用户名
    type: text
    required: true
  - id: password
    name: 密码
    type: password
    required: true

isps:
  - id: student
    name: 学生账号
    default: true
  - id: staff
    name: 教职工账号

check:
  method: GET
  url: "http://192.168.1.100/chkstatus"
  online_when:
    contains: '"result":1'
  offline_when:
    contains: '"result":0'

login:
  steps:
    - name: auth
      method: GET
      url: "http://192.168.1.100/portal/login?user=${username}@${isp}&pwd=${password}&ip=${ip}&v=${rand}"
      expect_status: 200
      success_when:
        json:
          path: result
          equals: "1"
      already_online_when:
        contains: "已经在线"
      fail:
        json_path: msg
```

## 通知监听自动恢复

国产 ROM（MIUI / HyperOS / ColorOS / OriginOS 等）在进程被杀或重启后，可能断开 `NotificationListenerService` 的系统绑定，导致收不到网关通知。应用内置多级自动恢复：

1. **官方 API 重绑**：`requestRebind`（部分 ROM 有效）。
2. **组件开关 toggle**：切换监听器启用状态触发系统重连，无需权限，全 ROM 通用。
3. **Root 强绑**：增强模式 + Root 时执行 `cmd notification allow_listener`。

触发时机：每次启动自动重绑（延迟约 1.2s）、设置页每 3 秒轮询监听状态、前台服务运行中断开即重连；流程带冷却与全局互斥，避免高频触发。

## 技术栈

- **UI**：Jetpack Compose（BOM 2026.08.00）
- **组件 / 模糊**：miuix-kmp 0.9.4
- **液态玻璃**：AndroidLiquidGlass / backdrop（Kyant0）
- **提权**：Shizuku 13.1.5（可选 Root）
- **最低系统**：Android 10（API 29）
- **目标系统**：Android 16（API 36）
- **编译 SDK**：Android 17（API 37）
- **构建**：Gradle 9.5.0 + AGP 9.3.2 + Kotlin 2.4.10

## 从源码构建

需要 JDK 17 与 Android SDK（含 platform 37、build-tools 37）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/`。签名使用 `schoolautologin-release.keystore`（alias = `schoolautologin`）；请勿将自己的密钥提交到公开仓库。

## 版本

- versionName：**1.1.3**
- versionCode：**162**
- applicationId：`com.luminaauth`

## 开源协议

本项目基于 **GNU GPL-3.0-or-later** 协议开源，详见 [LICENSE](LICENSE)。

致谢以下开源项目（各项目具体协议以其官方仓库 LICENSE 为准）：

- [SukiSU Ultra](https://github.com/SukiSU-Ultra/SukiSU_Ultra)（Pager 状态、液态 Dock、卡片等适配参考，GPL-3.0）
- [miuix-kmp](https://github.com/YuKongA/miuix-kmp)（Miuix 组件与模糊）
- [AndroidLiquidGlass](https://github.com/kyant0/AndroidLiquidGlass)（液态玻璃效果）
- [Shizuku](https://github.com/RikkaApps/Shizuku)（非 Root 提权）
