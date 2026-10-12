# 轻骑 · 实测版

一个简洁、轻量的 Android 电动车工具。原生 Java 与系统控件，无第三方运行时依赖，不包含导航、广告或账号系统。

**当前版本：0.1.5-field 本地实测专用版。可完整检查蓝牙连接和标准设备信息，还不能控车。**

## 能做什么

- 扫描附近 BLE 设备，按名称、完整或部分 MAC 地址筛选。
- 搜索不区分大小写，MAC 可省略冒号，也支持短横线、点或空格分隔。
- 显示完整 MAC 和信号强弱，设备顺序不会随信号变化跳动。
- 由用户选择自己的车辆，连接并发现 GATT 服务，展示可能匹配的接口线索。
- 一次采集广播原始数据、厂商与服务数据、服务/特征/描述符清单。
- 识别同服务内的 ADB1/ADB2 或 FF78/7800–7804 接口线索，在检查详情顶部显示；不代表已支持控车。
- 读取设备公开的标准名称、外观码、PnP 产品标识、HID 信息与报告描述、型号、制造商、软硬件及固件版本、标准电量（如提供）。标准电量未确认来自车辆动力电池，不用于首页车辆电量显示；HID 只读取描述信息，不读取按键事件。
- 测量最多三次连接信号，自动断开重连，对比两次接口清单。
- 支持测试备注，SQLite 自动保存全部检查和逐条过程日志；失败、超时、中途停止和退到后台也保留已有结果。历史每页 50 条，不自动淘汰。
- 一键导出包含数据库及所有报告的 ZIP 文件，不需要逐份复制粘贴。升级自动迁移旧版历史并保留原始偏好数据作为迁移备份。

电量、续航、蓝牙开关锁、寻车鸣笛、开座桶和感应解锁均**尚未实现**，界面中的相应入口处于禁用状态。接口特征匹配不代表已经识别车辆、通过车主鉴权或完成车型适配；目前没有已验证可控车的车型清单。

## 使用

需要 Android 12 或更新版本，以及 BLE 硬件。

1. 安装自行构建的 APK，开启手机蓝牙，允许轻骑使用“附近设备”权限。
2. 先在车辆官方 App 中完成正常绑定，确认车辆是否支持手机蓝牙控车。
3. 关闭官方 App 的蓝牙连接；如果它自动重连，可在系统应用设置中强行停止它，无需解绑车辆。
4. 在轻骑中点击“查找我的车”，输入名称或 MAC 筛选，只选择能确认属于自己车辆的设备。
5. 点击“开始完整检查”，可备注车型、固件及车辆当前状态。保持页面在前台，最多等待 90 秒。结果自动保存；从首页“检查记录”查看。所有测试完成后，点击“导出全部记录”，选择保存位置，将一个 ZIP 文件回传即可。

扫描持续约 15 秒，结束后仍可筛选列表，也可以重新搜索。找不到设备时，靠近车辆并按车辆说明唤醒蓝牙后重试。一次未发现不能证明不支持，距离、休眠、连接占用及系统扫描过滤均可能影响结果。

## 数据与权限

- 仅申请蓝牙扫描与连接权限，没有联网权限，不包含遥测或广告 SDK。
- 附近设备列表仅存于内存。已选设备、全部检查和过程日志保存在应用私有 SQLite 数据库 `diagnostics.db` 中；包含时间、版本、手机型号、备注、选中设备名称与 MAC、原始广播和厂商数据、接口、标准读取结果或错误。广播数据也可能含设备标识。
- 检查结束、超时或离开应用后断开连接。不读取车主凭据、不主动调用配对、不写入蓝牙特征或描述符、不发送控车指令。仅对白名单标准特征发起读取；若设备要求加密，Android 系统可能发起配对提示。
- 导出 ZIP 包含设备标识和原始广播，适合私下回传分析。导出位置由系统文件选择器决定，选择云盘时由该文件提供方保存。清除应用数据或卸载会删除本机数据库，请先导出需要保留的记录。

## 连接电脑直接取回数据

当前实测版沿用 `local.leo.qingqi` 包名，可覆盖升级旧版。Debug 构建允许通过 ADB 读取应用自己的数据库。测试完成、手机接回电脑后运行：

```sh
python3 scripts/pull_diagnostics.py --serial YOUR_DEVICE_SERIAL
```

脚本会先停止轻骑以保证读取期间无写入，下载到被 Git 忽略的 `evidence/`，然后检查数据库完整性。不会清除数据；若检查尚在进行，已提交的事件会保留，下次启动标记为中断。不需要从手机复制文本。

数据库分为 `devices`、`sessions`、`events`，分别保存已选设备、每次检查元信息/最终报告和时间顺序事件。只有用户选择开始检查的设备入库，附近扫描列表不持久化。杀进程时尚在写入队列中的少量事件可能丢失，已提交事件可在下次启动恢复查看。

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

数据库集成测试使用独立测试库，不修改实际检查数据：

```sh
./gradlew :app:assembleDebugAndroidTest
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s YOUR_DEVICE_SERIAL shell am instrument -w local.leo.qingqi.test/local.leo.qingqi.StorageTestRunner
```

Windows 可用 `gradlew.bat` 和 `py -3 scripts/check_contract.py`。安装了 Make 的环境也可运行 `make build check`。

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

```sh
adb devices
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

当前使用 Gradle 9.1.0、Android Gradle Plugin 8.13.2、SDK 36 和 Build Tools 36.0.0。首次构建需要联网下载构建依赖，应用本身不联网。

GitHub Tag 构建使用固定实测签名，与最初的本机实测 APK 签名一致，可覆盖升级并保留检查记录。产物仍是支持 ADB 取数的 Debug 实测版，不是 Google Play 正式发布包。普通本地构建默认使用当前机器的调试签名；其他机器构建的 APK 可能无法覆盖安装，请使用 GitHub Release 的固定签名版本。

## Tag 构建与发布

更新 `app/build.gradle` 中的 `versionName` 和递增的 `versionCode`，并在 `CHANGELOG.md` 增加同名版本条目，提交并推送到 `main` 后创建 annotated tag：

```sh
git tag -a v0.1.5-field -m "Release 0.1.5-field"
git push origin v0.1.5-field
```

每次发版使用新的版本和 Tag。普通 push / PR 运行构建与检查；`v*` Tag 触发独立的 `Tagged APK` 工作流，核对版本与 `main` 归属，运行回归测试、构建与 Lint，验证固定签名后发布 APK 和 `SHA256SUMS`。含 `-field` 等后缀的版本标为 GitHub 预发布，不设为 Latest。下载入口为 [GitHub Releases](https://github.com/leowzz/qingqi/releases)。

构建密钥由仓库 Actions Secrets 提供：`QINGQI_KEYSTORE_BASE64`、`QINGQI_STORE_PASSWORD`、`QINGQI_KEY_ALIAS`、`QINGQI_KEY_PASSWORD`。缺少配置或证书与 `app/signing-certificate.sha256` 不符时构建失败，避免使用临时签名发布。证书 SHA-256 是公开校验值；私钥与密码不提交。

维护者本机的密钥与密码备份保存在被忽略的 `.signing/`，应单独妥善备份；GitHub Secrets 无法取回明文。需要本地固定签名构建时，设置 `QINGQI_KEYSTORE` 为密钥绝对路径，并提供上述密码与别名环境变量。发布重跑保留已有 Release 正文，重新上传该 Tag 的 APK 和校验文件；构建失败时不会进入发布步骤。

## 参与开发

请先阅读 [贡献指南](CONTRIBUTING.md)、[兼容性与协议线索](docs/compatibility.md) 和 [安全说明](SECURITY.md)。版本变化见 [CHANGELOG](CHANGELOG.md)。

GitHub Actions 配置会对 push 和 PR 执行构建、Lint 与现有检查。它不连接实车，也不能证明控车兼容性。

### 一次到车旁的建议测试

先用官方 App 确认车辆能连接，再停止官方 App 占用连接。使用原车钥匙或官方 App 分别准备以下状态，每次在轻骑填写备注后执行完整检查：

1. 锁车并唤醒蓝牙，靠近车辆测试。
2. 用原车钥匙解锁后再次测试，比较广播、接口及标准读取结果。
3. 如需判断距离影响，保持车辆状态不变，在稍远处重测。

每次均会自动验证一次断开重连。未提供标准信息不等于不支持，需要结合私有协议继续适配。报告不会把“扫描到设备”或“连接成功”当成车主认证或控车成功。

接下来的重点是验证真实车型的硬件与正常车主凭据来源、实现认证与只读车辆状态，然后逐项验证用户主动触发的控制功能。感应解锁需要另外验证后台行为和可靠性。

## 许可证

项目原创代码与文档采用 [MIT](LICENSE)。Gradle Wrapper 保留其原始许可，见 [第三方声明](THIRD_PARTY_NOTICES.md)。

本项目为独立开发，与爱玛或其他车辆厂商没有隶属关系。仓库不包含官方 APK、反编译源码、厂商资源或车主密钥。
