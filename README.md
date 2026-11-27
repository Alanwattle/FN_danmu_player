# 潮汐放映

潮汐放映是一个面向 Android 手机的原生影音客户端，用于连接飞牛影视和 danmu_api。它提供媒体库浏览、剧集管理、续播、弹幕和完整播放器控制。

> **AI 生成声明**
>
> 本项目的产品设计、交互方案、源代码、原创示例封面、示例视频、弹幕示例和 Logo 均由 GPT（OpenAI Codex）根据用户需求生成与整理。作者署名为 **GPT（OpenAI Codex）**。项目与飞牛、fnOS、FN Connect、danmu_api 或 Android 官方没有隶属关系。

## 项目状态

| 项目 | 状态 |
| --- | --- |
| Android 原生客户端 | 已完成 |
| 飞牛影视登录和片库 | 已完成 |
| FN ID / FN Connect 定位 | 已完成 |
| danmu_api 自动匹配和手动匹配 | 已完成 |
| Media3 播放器 | 已完成 |
| 示例片库 | 已完成 |
| 发布签名 | 未配置，当前 APK 使用开发签名 |

当前版本是 **1.1.3**，最低支持 Android 8.0（API 26）。

## 下载

- [APK 1.1.3](release/ushio-1.1.3-logo-android.apk)
- [Logo SVG](branding/logo.svg)
- [首页截图](release/home.png)
- [播放器截图](release/player.png)

安装包是开发签名版本，适合测试和个人使用。正式分发前，请替换为自己的发布密钥。

## 功能

### 媒体库

- 读取飞牛影视媒体库和影片列表
- 电影、剧集、季、集层级浏览
- 片名搜索
- 首页推荐和继续观看
- 动漫片库每日随机推荐 3 部影片并自动切换
- 首页区块稳定布局和封面缓存
- 飞牛影视播放进度回传
- 飞牛影视海报和背景图加载
- 支持飞牛影视返回的转码源和原画源

### 登录

- 飞牛影视地址、账号和密码登录
- FN ID 登录
- FN Connect 地址发现和 HTTPS 中继激活
- 支持飞牛影视网页客户端签名
- 兼容签名错误响应 invalid sign
- 使用 Android Keystore 加密保存会话
- 支持断开连接和清除本机保存的会话

填写 FN ID 时只需输入类似下面的标识，不需要协议和端口：

    demo-ushio-4827

FN ID 用于定位 NAS，仍需填写飞牛影视本地账号和密码。

### 弹幕

- danmu_api 地址测试和保存
- 支持带路径令牌的 danmu_api 地址
- 文件名和标题自动匹配
- 片源搜索
- 动漫和剧集选择
- 弹幕开关
- 字体大小、不透明度、显示范围和时间偏移
- 弹幕时间轴跟随播放器暂停、跳转和拖动

### 播放器

播放器基于 AndroidX Media3，包含以下操作：

| 操作 | 效果 |
| --- | --- |
| 单击画面 | 显示或隐藏播放控件 |
| 双击左侧、中央或右侧 | 后退 10 秒、播放或暂停、前进 10 秒 |
| 水平滑动 | 预览目标时间，松手跳转 |
| 左侧垂直滑动 | 调整屏幕亮度 |
| 右侧垂直滑动 | 调整媒体音量 |
| 播放时长按 | 临时使用 2 倍速 |
| 播放设置 | 播放源、速度、画面比例、音轨、字幕和解码模式 |
| 锁定按钮 | 锁定画面操作 |
| 画中画按钮 | 进入系统画中画 |
| 选集按钮 | 切换剧集 |
| 弹幕设置 | 开关、匹配、样式和偏移调节 |

字幕支持 SRT、ASS、SSA、VTT 和 TTML。实际解码能力取决于 Android 设备可用的系统解码器。

## 连接飞牛影视

1. 打开 App 的「连接」页面。
2. 在「服务器地址或 FN ID」中填写飞牛影视地址或 FN ID。
3. 填写飞牛影视本地账号和密码。
4. 点击「连接飞牛影视」。
5. 等待首页读取媒体库。

普通地址示例：

    https://nas.example.com:5667
    http://192.168.1.10:5666

FN ID 示例：

    demo-ushio-4827

FN ID 流程会请求 FN Connect 定位接口，选择可用的 HTTPS 地址，访问 /v 激活中继，然后继续调用飞牛影视 API。

## 连接 danmu_api

在「弹幕服务」中填写 danmu_api 部署地址：

    http://192.168.1.10:9321

如果服务启用了路径令牌：

    http://192.168.1.10:9321/你的令牌

保存前点击「测试并保存」。播放器会优先使用当前影片标题和集数自动匹配，失败时可以在播放器的「调节」中手动搜索。

## 构建

需要 JDK 17、Android SDK Platform 35、Android Build Tools 35.0.0 和 Gradle Wrapper 8.11.1。

Windows PowerShell：

    $env:ANDROID_HOME = "C:\Android\Sdk"
    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug

APK 输出路径：

    app/build/outputs/apk/debug/app-debug.apk
    app/build/outputs/apk/release/app-release.apk

Release 构建会读取项目根目录的 `keystore.properties`。文件内容示例：

    storeFile=C:/Codex/android/ushio-release.jks
    storePassword=填写 keystore 密码
    keyAlias=填写 alias
    keyPassword=填写 alias 密码

`keystore.properties` 已加入 `.gitignore`，不会提交到仓库。缺少该文件时，Release 构建会回退到 Android Debug keystore，仅适合本地测试。

## 项目结构

    app/src/main/java/dev/ushio/player/
    ├── LibraryActivity.java      首页、片库、搜索和连接设置
    ├── PlaybackActivity.java     Media3 播放器、手势、字幕和画中画
    ├── FnClient.java             飞牛影视 API、登录和 FN Connect
    ├── DanmuClient.java          danmu_api 请求和匹配
    ├── DanmuView.java            弹幕时间轴和绘制
    ├── Preferences.java          Android Keystore 加密存储
    ├── Title.java                媒体条目模型
    ├── Demo.java                 离线示例片库
    └── Ui.java                   原生 UI 组件和图形图标

    app/src/main/res/
    ├── drawable/ic_ushio.xml     App Logo 的 Android Vector Drawable
    ├── drawable-nodpi/           示例片库原创封面
    └── values/styles.xml         深色主题

    branding/
    └── logo.svg                  Logo 源文件

## 测试

构建时执行：

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug

已覆盖的自动化登录场景：

- 网页签名登录
- Token 签名登录
- 登录后片库请求触发签名切换
- 旧版登录接口回退
- 密码错误不进行无意义重试

示例片库可以不连接 NAS 直接运行。飞牛影视和 danmu_api 的真实连接仍取决于 NAS 版本、FN Connect 状态、网络环境和服务端配置。

## API 与第三方项目

- [飞牛影视桌面端参考实现](https://github.com/QiaoKes/fntv-electron)
- [FlyNarwhal](https://github.com/FNOSP/FlyNarwhal)
- [飞牛影视 API 实测记录](https://github.com/jimboo7339/fntv_danmu_all/blob/main/doc/%E9%A3%9E%E7%89%9B%E5%BD%B1%E8%A7%86API%E6%96%87%E6%A1%A3.md)
- [danmu_api](https://github.com/huangxd-/danmu_api)
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3)
- [飞牛官方连接说明](https://help.fnnas.com/articles/v1/start/install-app)

本项目复用了公开 API 协议和公开客户端行为参考。相关上游项目的许可证和使用条款仍然适用于其各自的代码与服务。

## 隐私与安全

- 飞牛影视会话使用 Android Keystore 加密后保存在本机。
- danmu_api 地址使用相同的本机加密存储。
- App 不内置用户账号、密码、Cookie、Token 或个人媒体信息。
- 示例素材为项目内置的离线演示资源。
- 使用公网访问时建议使用有效 HTTPS 证书，并在 FN Connect 或 NAS 端启用必要的访问保护。

## 版本记录

### 1.1.3

- 首页顶部推荐改为动漫片库每日随机卡片轮播
- 探索片库切换时保持顶部推荐和继续观看区域稳定
- 增加封面缓存和并发请求去重
- 修复 Android 15+ 普通页面与系统状态栏重叠
- 升级目标 API 到 35

### 1.1.2

- 重做抽象影音 Logo
- 更新 Android Vector Drawable 和 SVG Logo
- 保持 FN ID、播放器和弹幕功能不变

### 1.1.0

- 增加 FN ID / FN Connect 定位
- 增加中继激活和 HTTPS 地址选择
- 增加 FN ID 会话复用

### 1.0.1

- 修复飞牛影视 invalid sign 兼容逻辑
- 优先使用网页客户端签名
- 增加登录回归测试

### 1.0.0

- 初始 Android 客户端
- 媒体库、播放器、弹幕和示例片库

## 作者与生成方式

作者：**GPT（OpenAI Codex）**

本项目由 GPT 根据用户需求自主完成，包括产品结构、UI、Android Java 代码、协议适配、播放器交互、Logo、示例媒体资源和文档。项目发布前仍建议由维护者进行安全审查、依赖审查、实机兼容性测试和发布签名配置。

## 许可证

本项目当前未声明开源许可证。除非维护者另行添加许可证文件，否则默认不授予复制、修改、再发布或商业使用许可。
