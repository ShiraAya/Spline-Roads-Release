# Spline Roads 2.0

Spline Roads 是 Minecraft 的道路建造模组，提供样条道路、路口、匝道、立交、桥隧、标志与信号灯，以及可选的 MTR 铁路路基联动。

## 安装

使用 Minecraft 1.20.1、Forge 47.4.20 和 Java 17。把 Releases 中的 `splineroads-2.0.jar` 放入 `mods` 目录。联机时，客户端和服务端使用同一版本。MTR 4.0.5 为可选依赖。

## 构建

安装 JDK 17，在仓库根目录运行 `./gradlew jar`（Windows 使用 `gradlew.bat jar`）。Gradle 会下载 Forge 和编译所需的 MTR 依赖。安装包生成在 `build/libs/splineroads-2.0.jar`。

2.0 是首个公开版本，基于 1.0.80 的生产源码。旧测试版存档不在支持范围内。

代码采用 [MIT 许可](LICENSE)。随附资源和构建工具的许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。
