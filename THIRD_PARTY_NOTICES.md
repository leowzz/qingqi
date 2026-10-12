# 第三方声明

轻骑的原创源码、图标和文档采用根目录的 MIT 许可证。当前应用没有第三方运行时依赖；构建工具不会作为完整发行版打包进 APK。

## Gradle Wrapper 9.1.0

`gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar` 来自 Gradle，使用 Apache License 2.0，保留原始版权声明。

为保留上游许可与声明，本仓库附带 Gradle 9.1.0 发行版原文：

- [Gradle LICENSE](licenses/gradle-LICENSE.txt)
- [Gradle NOTICE](licenses/gradle-NOTICE.txt)

这些上游文件也说明完整 Gradle 发行版涉及的其他组件；本仓库只提交 Wrapper，不分发完整 Gradle。来源：https://github.com/gradle/gradle/tree/v9.1.0

Wrapper JAR 校验值来自 https://services.gradle.org/distributions/gradle-9.1.0-wrapper.jar.sha256，分发包校验值来自 https://services.gradle.org/distributions/gradle-9.1.0-bin.zip.sha256。

Android SDK、Android Gradle Plugin 等由构建工具另行下载，遵循各自许可证。
