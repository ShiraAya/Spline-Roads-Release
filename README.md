# Spline Roads 2.1.1

Spline Roads 是 Minecraft 的道路建造模组，提供样条道路、路口、匝道、立交、桥隧、标志与信号灯，以及可选的 MTR 铁路路基联动。

## 安装

使用 Minecraft 1.20.1、Forge 47.4.20 和 Java 17。把 `splineroads-2.1.1.jar` 放入 `mods` 目录。联机时，客户端和服务端使用同一版本。MTR 4.0.5 为可选依赖。

## 构建

安装 JDK 17，在仓库根目录运行 `./gradlew jar`（Windows 使用 `gradlew.bat jar`）。Gradle 会下载 Forge 和编译所需的 MTR 依赖。安装包生成在 `build/libs/splineroads-2.1.1.jar`。

2.0 是首个公开版本，基于 1.0.80 的生产源码。2.1.1 沿用道路数据格式 73、网络协议 128，支持读取格式 71/72 及不含已退役自定义标志的格式 70；其他旧格式或未来格式会明确拒绝并保留原文件，不会生成空道路库覆盖它。既有结构的几何调整仍需主动编辑并保存。

本轮变更与验证见 [2.1.1 验收记录](docs/verification/2.1.1-feedback.md)。

代码采用 [MIT 许可](LICENSE)。随附资源和构建工具的许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。
