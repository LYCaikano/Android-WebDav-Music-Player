# WebDav Music Player

**WebDav Music Player** 是一款基于 Android 平台的现代化流媒体音乐播放器。它专为私有云存储用户设计，支持通过 WebDAV 协议（兼容 NAS、Alist、群晖等）直接流式播放高品质音频。项目完全基于 **Jetpack Compose** 构建，~~遵循 **Material 3** 设计规范~~，实现了沉浸式的 Edge-to-Edge 视觉体验，并内置了~~强大的~~元数据管理与本地缓存机制。

## 📅 功能路线图
### ✅ 已支持功能

  - [x] **WebDAV 核心支持**
      - [x] 多账号管理与切换
      - [x] HTTP/HTTPS 协议支持（含自签名证书兼容）
      - [x] 自定义路径深度扫描
  - [x] **音频播放能力**
      - [x] 流式播放 (Streaming)
      - [x] 广泛的格式支持 (MP3, FLAC, WAV, M4A, AAC, OGG, OPUS)
      - [x] 播放模式切换 (顺序/随机/单曲循环)
      - [x] 后台播放服务与媒体通知栏控制
  - [x] **智能媒体库**
      - [x] 深度元数据扫描 (基于 ID3 Tags 读取歌名/歌手/专辑)
      - [x] 智能专辑归类 (基于文件夹结构的元数据推导与纠错)
      - [x] 封面加载与本地持久化缓存
  - [x] **数据管理**
      - [x] 边听边存 (自动缓存)
      - [x] 离线下载管理
      - [x] 歌单系统 (收藏夹、下载列表、自定义歌单)
      - [x] 批量操作 (批量添加至歌单、批量删除)
  - [x] **UI/UX 体验**
      - [x] Material 3 动态主题
      - [x] 深色/浅色模式切换
      - [x] 全面屏手势适配 (Edge-to-Edge)
      - [x] 平滑的共享元素转场动画

### 🚀 计划中功能
  - [ ] **歌词支持** (LRC 歌词解析与桌面歌词显示)
  - [ ] **搜索增强** (支持拼音/模糊搜索)
  - [ ] **云端同步** (歌单配置云端备份)

## 🛠️ 技术架构

本项目采用现代化的 Android 开发技术栈，架构遵循 MVVM 模式与单一数据源原则 (SSOT)。

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **语言** | Kotlin | 100% Kotlin 代码 |
| **UI 框架** | Jetpack Compose | Material 3 Design System |
| **架构模式** | MVVM | Model-View-ViewModel |
| **依赖注入** | Dagger Hilt | 依赖管理与模块解耦 |
| **异步处理** | Coroutines & Flow | 协程与响应式数据流 |
| **网络请求** | OkHttp 4 | WebDAV 协议交互核心 |
| **媒体播放** | AndroidX Media3 | 基于 ExoPlayer 的媒体会话管理 |
| **本地存储** | Room Database | SQLite 对象映射与数据持久化 |
| **配置存储** | DataStore | 类型安全的配置管理 |
| **图片加载** | Coil | 异步图片加载与内存优化 |
| **元数据解析** | JAudiotagger | 音频标签读取 |

## 📂 项目结构概览

```text
top.sparkfade.webdavplayer
├── data                 # 数据层 (Repository Pattern)
│   ├── local            # Room DAO, Database Entities
│   ├── model            # Data Classes
│   ├── remote           # WebDAV Parser, Network Response
│   └── repository       # 业务逻辑与数据仲裁
├── di                   # Hilt DI Modules
├── service              # MediaSessionService (后台播放)
├── ui                   # 界面层
│   ├── components       # 通用 Compose 组件
│   ├── screens          # 业务页面 (Main, Player, Login...)
│   ├── theme            # Material 3 主题配置
│   └── viewmodel        # 状态管理与 UI 逻辑
└── utils                # 工具类与扩展函数
```

## ⚡ 快速开始
#### 从action下载构建好的apk或自行构建

### 环境要求

  * Android Studio Iguana (2023.2.1) 或更高版本
  * Java Development Kit (JDK) 17 或更高版本
  * Android SDK API 34

### 构建与安装

使用 Android Studio 或 Gradle 命令行来构建与安装。

#### 方式一：使用 Android Studio

1.  **下载源码**: 执行
   ```bash
      git clone https://github.com/LYCaikano/Android-WebDav-Music-Player.git
   ```
2.  **导入项目**: 打开 Android Studio，选择 `Open` 并指向项目根目录。
3.  **同步依赖**: 等待 Gradle Sync 完成。
4.  **运行安装**: 连接您的 Android 设备或启动模拟器，点击 Android Studio 顶部工具栏的 **Run 'app'** 按钮（绿色三角形）。IDE 将自动编译并安装调试版本。

#### 方式二：使用 Gradle 

执行命令：

```bash

git clone https://github.com/LYCaikano/Android-WebDav-Music-Player.git

cd Android-WebDav-Music-Player

./gradlew assembleDebug

```
生成的 APK 路径: `app/build/outputs/apk/debug/app-debug.apk`
