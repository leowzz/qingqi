# 轻骑 · Qingqi

一个简洁、轻量的 Android 电动车工具。原生 Java 与系统控件，无第三方运行时依赖，不包含导航、广告或账号系统。

**当前版本：0.1.2 适配预览。现在能检查蓝牙连接，还不能控车。**

## 能做什么

- 扫描附近 BLE 设备，按名称、完整或部分 MAC 地址筛选。
- 搜索不区分大小写，MAC 可省略冒号，也支持短横线、点或空格分隔。
- 显示完整 MAC 和信号强弱，设备顺序不会随信号变化跳动。
- 由用户选择自己的车辆，连接并发现 GATT 服务，展示可能匹配的接口线索。
- 自动保存最近一次连接检查结果，支持查看和复制。

电量、续航、蓝牙开关锁、寻车鸣笛、开座桶和感应解锁均**尚未实现**，界面中的相应入口处于禁用状态。接口特征匹配不代表已经识别车辆、通过车主鉴权或完成车型适配；目前没有已验证可控车的车型清单。

## 使用

需要 Android 12 或更新版本，以及 BLE 硬件。

1. 安装自行构建的 APK，开启手机蓝牙，允许轻骑使用“附近设备”权限。
2. 先在车辆官方 App 中完成正常绑定，确认车辆是否支持手机蓝牙控车。
3. 关闭官方 App 的蓝牙连接；如果它自动重连，可在系统应用设置中强行停止它，无需解绑车辆。
4. 在轻骑中点击“查找我的车”，输入名称或 MAC 筛选，只选择能确认属于自己车辆的设备。
5. 点击“检查连接”，保持页面在前台，最多等待约 18 秒。完成后可查看或复制结果；下次打开可从首页“上次检查结果”找回。

扫描持续约 15 秒，结束后仍可筛选列表，也可以重新搜索。找不到设备时，靠近车辆并按车辆说明唤醒蓝牙后重试。一次未发现不能证明不支持，距离、休眠、连接占用及系统扫描过滤均可能影响结果。

## 数据与权限

- 仅申请蓝牙扫描与连接权限，没有联网权限，不包含遥测或广告 SDK。
- 附近设备列表仅存于内存。最近一次连接检查保存在应用私有存储中，新结果覆盖旧结果；包含时间、应用版本、Android 版本、选中设备名称、MAC、服务信息或错误。
- 检查结束、超时或离开应用后断开连接。不读取车主凭据、不发起绑定、不写入蓝牙特征或描述符、不发送控车指令。
- “复制结果”会把报告放入系统剪贴板。公开反馈前请遮盖真实 MAC、设备名称中的身份信息等。清除应用数据或卸载会删除本地报告，系统备份已禁用。

## 从源码构建

准备 JDK 17、Python 3.9+、Android SDK Command-line Tools；使用项目自带的 Gradle Wrapper，无需全局安装 Gradle。

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

设置 `ANDROID_HOME` 指向你的 Android SDK，或在本机的 `local.properties` 中写入 `sdk.dir=你的SDK绝对路径`。该文件不会提交到 Git。

```sh
./gradlew :app:assembleDebug :app:lintDebug
python3 scripts/check_contract.py
```

Windows 可用 `gradlew.bat` 和 `py -3 scripts/check_contract.py`。安装了 Make 的环境也可运行 `make build check`。

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

```sh
adb devices
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

当前使用 Gradle 9.1.0、Android Gradle Plugin 8.13.2、SDK 36 和 Build Tools 36.0.0。首次构建需要联网下载构建依赖，应用本身不联网。

Debug APK 使用开发机器的调试签名，不是稳定发布包。不同机器构建的 APK 可能无法互相覆盖安装；卸载重装会删除本地检查结果。正式发布签名和自动发布流程尚未配置。

## 参与开发

请先阅读 [贡献指南](CONTRIBUTING.md)、[兼容性与协议线索](docs/compatibility.md) 和 [安全说明](SECURITY.md)。版本变化见 [CHANGELOG](CHANGELOG.md)。

GitHub Actions 配置会对 push 和 PR 执行构建、Lint 与现有检查。它不连接实车，也不能证明控车兼容性。

接下来的重点是验证真实车型的硬件与正常车主凭据来源、实现认证与只读车辆状态，然后逐项验证用户主动触发的控制功能。感应解锁需要另外验证后台行为和可靠性。

## 许可证

项目原创代码与文档采用 [MIT](LICENSE)。Gradle Wrapper 保留其原始许可，见 [第三方声明](THIRD_PARTY_NOTICES.md)。

本项目为独立开发，与爱玛或其他车辆厂商没有隶属关系。仓库不包含官方 APK、反编译源码、厂商资源或车主密钥。
