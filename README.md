# WatermarkDemo

用手机相机对准印刷件，实时把纸上的隐形（黄色）水印图案增强出来，供人眼判读。

> 范围：“增强显示”，

## 开发环境

| 项目 | 版本 |
|---|---|
| JDK | 21（本机）；代码目标为 Java 11 |
| Gradle | 9.5.0（wrapper） |
| AGP / Kotlin | 8.13.2 / 2.1.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 |
| 主要依赖 | Compose BOM 2024.10.01、CameraX 1.4.1、OpenCV 4.9.0、Timber 5.0.1 |

## 构建与安装

1. 在根目录建 `local.properties`，写入 `sdk.dir=<你的 Android SDK 路径>`。
2. 构建：

   ```bash
   ./gradlew assembleDebug
   ```

3. 安装（多台设备时用 `-s` 指定序列号）：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

注意：

- **工程路径不能含中文**，否则 AGP 会拒绝构建。
- APK 约 98 MB，因为带了 OpenCV 原生库（仅保留 arm64-v8a 和 x86_64）。

## 使用

打开后授予相机权限，界面上方是处理后的画面，下方是控制面板：

- **显示阶段**：通道、检测、矫正、光照归一化、高通、曲线、均衡化、拉伸，可只看某一阶段。
- **来源通道**：B / G / R / 亮度，默认 B。
- **纸面检测 + 透视矫正**：找到纸面四边形并拉正，找不到时自动用整幅画面。
- **光照归一化**：原库没有，用来压阴影和反光，默认关。
- **滤波与曲线参数**：卷积核中心值、形态学核、`extract` / `useNew` 两种曲线。
- **保存**：下一帧同时存原始帧和当前阶段输出为 PNG，位置是 `图片/WatermarkDemo`，可拿回桌面脚本复现。

## 处理管线

```
RGBA 帧 → 取通道 → [纸面检测 + 透视矫正] → [光照归一化]
        → 3×3 锐化 + 形态学 → 逐像素曲线 → 直方图均衡 → 分位拉伸
```

## 代码结构

```
app/src/main/java/com/example/watermarkdemo/
├── MainActivity.kt / WatermarkApp.kt   入口，初始化 OpenCV 与 Timber
├── processing/
│   ├── WatermarkEnhancer.kt            按阶段执行整条管线
│   ├── PaperDetector.kt                纸面四边形检测与透视矫正
│   ├── EnhanceParams.kt                参数、阶段、常量
│   ├── FrameConverter.kt               CameraX 帧 → 直立的 OpenCV Mat
│   └── FrameSaver.kt                   通过 MediaStore 保存 PNG
└── ui/
    ├── MainViewModel.kt                状态与分析线程
    └── MainScreen.kt                   Compose 界面
```

架构为单 Activity + MVVM。分析线程只保留最新一帧，处理不过来的帧会被丢弃。

## 相关文档与脚本

- 方案说明书：[docs/水印检测方案说明书.md](docs/水印检测方案说明书.md)，含分析依据、详细参数、验证状态和风险。
- 离线复现脚本：`yellow_enhance.py`（Python + OpenCV），在仓库外的 `水印/` 目录，管线与本工程一致，适合用保存下来的原始帧调参。

## 已知限制

- 目前的样图全是屏幕截图，黄色点阵已丢失，**没有真正看到过隐形水印**，需要手机相机原始帧才能验证。
- 纸面检测在真实样图上未检测到四边形（纸铺满画面或被物体切碎），只证明回退路径可用。
- `useNew` 曲线的 `at255`、`rt0` 默认值未知，工程里先设为 0，需现场调。
