# 白云门禁钥匙（BaiyunKeys）

广州市白云区「平安回家」蓝牙门禁的 Android 开锁工具——协议与流程移植自开源微信小程序
[Soufock/BaiyunKeys-preview](https://github.com/Soufock/BaiyunKeys-preview)，
并参考 Android 原版 [dogproton/SafeBaiyun](https://github.com/dogproton/SafeBaiyun)。

## 功能

- **BLE 蓝牙开锁**：A5 帧协议 + DES/ECB 加密，MAC 直连优先、失败自动回退扫描匹配
- **多门禁管理**：本地保存多套门禁参数，随时切换
- **数据库提取配置**：从官方 App 本地数据库（`t_device` 表）直接读取 MAC / Key / 蓝牙名，
  应用内自动解析导入——**无需联网、无需账号、不受服务端策略影响**
- **实时协议日志**：首页查看完整 BLE 交互过程，便于排查
- **快速开锁**：打开 App 自动执行一次开锁

## 使用方法

1. 安装 APK（Android 5.0+，授予蓝牙/定位权限）
2. 进入「配置」页 → 「数据库提取」→ 按内置指南导出官方 App 的数据库文件夹
   （Root 文件管理器复制 / MIUI 备份解压 / 电脑 `adb backup` 三种方式，指南内有详细步骤）
3. 用「选择数据库文件夹」选中该文件夹，应用自动解析出门禁列表，点击导入
4. 回到首页选择门禁，点击开锁

> 导出前请先在官方「平安回家」App 中登录并打开门禁列表，确保数据库内有数据。

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 34）
./gradlew assembleDebug        # 调试版
./gradlew assembleRelease      # 发布版
```

- `settings.gradle.kts` 与 `gradle-wrapper.properties` 默认使用国内镜像
  （阿里云 Maven / 腾讯云 Gradle），海外用户可按需改回官方地址
- 签名库 `baiyunkeys.jks` **不在仓库内**：没有签名库时 release 会自动退回 debug 签名，
  保证 clone 后可直接构建；如需自己的发布签名，在项目根目录放入签名库并修改
  `app/build.gradle.kts` 中的 `signingConfigs` 配置

## 技术要点

- Kotlin + Jetpack Compose，minSdk 21 / targetSdk 34
- BLE 协议：`A5` 帧头 + 命令字 + 设备标识 + DES 密文 + 校验和 + `5A` 帧尾；
  握手指令 `A5 14 05`（20 字节）即开锁触发，门锁回 `cmd 04` / `type 87` 反馈结果
- GATT 操作严格串行（逐个等 CCCD 写入回调后再读种子），兼容"通知即种子"的固件行为
- 数据库解析：兼容扁平/分组两种返回结构，列名大小写不敏感，MAC 自动归一化

## 免责声明

- 本项目仅供学习交流，**永久免费**，禁止用于任何商业或非法用途
- 应用**不申请网络权限**，所有门禁参数仅保存在本机，清除应用数据即可彻底移除
- 门禁参数来源于用户本机官方 App 的数据库，提取过程完全离线完成
- 使用本项目造成的一切后果由使用者自行承担

## 目录结构

```
app/src/main/java/cn/baiyunkeys/app/
├── core/          # 协议层：HexUtil / DesCrypto / LockBiz / BleProtocol
├── data/          # DbExtractor（数据库提取）/ ConfigRepository / DoorConfig
├── ble/           # BleManager（GATT 流程）+ GattCallback.java
├── ui/            # Compose 界面与 ViewModel
└── MainActivity.kt
```
