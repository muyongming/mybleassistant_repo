# BLE 调试助手（FastBle 复刻版）

对 `BLE调试助手_2.0.apk`（`cn.wch.bledemo`，WCH 沁恒）做逆向分析后，用
[FastBle](https://github.com/Jasonchenlijian/FastBle) 重写的同功能 App。
重点强化了原版最有价值的能力：**连续快速收发高速数据包时不丢数据**。

---

## 一、原版功能对应关系

| 原版类/界面 | 本工程对应文件 | 说明 |
|---|---|---|
| `ScanActivity` / `ScanFragment` | `ui/ScanActivity.java`、`ui/adapter/DeviceAdapter.java` | 扫描、名称/地址/MAC/广播包/RSSI 过滤、收藏、已配对、广播包详情、连接 |
| `FilterJson` + `fragment_dialog_filter` | `ui/dialog/FilterDialogFragment.java` | 名称、地址、广播包 HEX、RSSI 阈值、只看收藏、只看 LE-only、只看无名称 |
| `AdvDetailDialogFragment` | `ui/dialog/AdvDetailDialogFragment.java` | 广播包按 AD Structure 解析 + 一键复制 |
| `MainActivity` + `menu/main` | `ui/MainActivity.java`、`res/menu/menu_main.xml` | 服务页/日志页 Tab；连接、断开、修改 MTU、读取 PHY、设置偏好 PHY、连接优先级、导出日志 |
| `MainFragment` + `ExpandListAdapter` | `ui/fragment/MainFragment.java`、`ui/adapter/ServiceTreeAdapter.java` | Service → Characteristic → Descriptor 三级展示，名称/UUID/Properties，读、写入口 |
| `WriteDialogFragment`（`fragment_dialog_write`） | `ui/dialog/WriteDialogFragment.java` | 单次/定时/连续/文件发送、HEX 开关、实时速度、发送间隔(≥10ms)、随机包长、进度条、Write Default / Write No Response |
| `ReadDialogFragment`（`fragment_dialog_read`） | `ui/dialog/ReadDialogFragment.java` | 读取特征值、开关 notify/indicate、HEX/ASCII、实时显示或保存至文件、接收计数与速率 |
| `LogFragment`（`LogJson`/`LogAdapter`） | `ui/fragment/LogFragment.java`、`ui/adapter/LogAdapter.java` | 暂停/继续、清空、复制到剪贴板、导出文件 |
| `SetMTUDialogFragment` | `ui/dialog/MtuDialogFragment.java` | 23~517 范围，限制同原版 |
| `SetPhyDialogFragment` | `ui/dialog/PhyDialogFragment.java` | TX/RX PHY 1M / 2M / Coded + S2/S8 |
| `SaveLogDialogFragment`/`SaveFileDialogFragment` | `ui/dialog/SaveFileDialogFragment.java` | 文件名输入 + 目录展示 |
| `PeripheralActivity`（从机模式） | `ui/PeripheralActivity.java` | 手机作为 GATT Server + 广播，收发、MTU、速率、向主机发送 |
| `SharePrefUtil` | `util/Prefs.java` | 收藏、历史、过滤条件、发送/接收配置持久化 |
| `WCHBluetoothManager` | `core/BleController.java` | 用 FastBle 统一封装替代 WCH 私有库 |

---

## 二、目录结构

```
BleDebugAssistant/
├─ settings.gradle / build.gradle / gradle.properties
└─ app/
   ├─ build.gradle
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/fastble/bledebugassistant/
      │  ├─ BleDebugApp.java              # FastBle 初始化
      │  ├─ core/
      │  │  ├─ BleController.java         # ★ FastBle 统一封装（唯一需要适配版本的文件）
      │  │  ├─ BleTxEngine.java           # ★ 发送引擎：分包 + 串行回调 + 重传（不丢包）
      │  │  ├─ BleRxEngine.java           # ★ 接收引擎：队列 + 批量格式化 + 文件落盘（不丢包）
      │  │  ├─ LogRepository.java         # 日志仓库（节拍批量刷 UI）
      │  │  └─ RateMeter.java             # 速率统计
      │  ├─ model/{Beans,LogItem}.java
      │  ├─ ui/…                          # Activity / Fragment / Adapter / Dialog
      │  └─ util/{HexUtil,UuidUtil,Prefs,TimeUtil,ToastUtil}.java
      └─ res/…                            # 布局、菜单、颜色、字符串、图标
```

---

## 三、“不丢数据”是怎么做到的

### 1. 发送方向（`BleTxEngine`）

| 措施 | 原因 |
|---|---|
| **按 `MTU-3` 分包** | ATT 头占 3 字节，超长写会被协议栈拒绝或截断 |
| **同一时刻只有一个 write 在飞** | Android `BluetoothGatt` 是单操作模型。不等回调就发下一包，`writeCharacteristic()` 会返回 `false`，数据直接被丢掉 —— 这是绝大多数“高速发送丢包”的真正原因 |
| **`CountDownLatch` 等 `onWriteSuccess`** | 每包必须确认完成才发下一包，顺序与完整性都得到保证 |
| **失败/超时重发同一包（3 次）** | 不跳包，不会出现中间缺一段 |
| **最终失败抛错并上报** | 绝不静默丢数据：UI 弹“发送失败”、日志记错误、进度停在真实位置 |
| **精确间隔** | `elapsedRealtimeNanos` 计算剩余时间，避免 `Thread.sleep` 抖动 |
| **文件发送用流式窗口** | 只有 1KB 级缓冲，几 GB 文件也不 OOM |

### 2. 接收方向（`BleRxEngine`）

| 措施 | 原因 |
|---|---|
| **BLE 回调线程只做 `queue.offer(bytes)`** | 回调里做 HEX 格式化/写文件/刷新 UI 会让协议栈缓冲堆积，最终丢通知包 |
| **独立消费线程批量出队**（≤512 包或 20ms 一批） | 一次格式化、一次 UI 回调，UI 消息量下降 1~2 个数量级 |
| **“保存至文件”直接写原始字节**（256KB 缓冲） | 与屏幕显示解耦，落盘不受 UI 限流影响 |
| **只截断“显示缓存”（64KB）** | 长时间高速接收不 OOM、不卡顿，但字节数和文件内容完整 |
| **无界队列** | 生产者（回调线程）永不被阻塞，慢磁盘也不会反压协议栈 |

### 3. 日志方向（`LogRepository`）

任意线程 `add()` 只入队；主线程 60ms 节拍统一 `notifyItemRangeInserted`；
显示列表上限 20000 条（只限制显示，导出/落盘不受影响）。

---

## 四、实测不丢包的推荐参数

1. **先改 MTU**：`修改MTU` → 247（或设备支持的最大值），单包 244 字节，
   相比默认 20 字节吞吐提升 12 倍。
2. **PHY 用 2M**：`设置偏好PHY` → TX/RX 都勾 `LE 2M`（需 Android 8.0+ 且设备支持）。
3. **连接优先级 HIGH**：菜单 `设置连接优先级` → HIGH（连接间隔缩短到 7.5~15ms）。
4. **写类型选 `Write No Response`**：吞吐最高；需要逐包确认时用 `Write Default`。
5. **发送间隔填 0**（连续发送）：引擎内部按回调串行，本身就不会拥塞。
6. **接收端“保存至文件”**：屏幕 HEX 刷新会消耗 CPU，落盘模式吞吐最高。

---

## 五、如何验证零丢包

1. 准备一个 1MB 以上的测试文件（内容可用递增字节）。
2. 主机：`服务页 → 可写特征值 → 文件发送`，勾选 HEX 无关；记录发送字节数。
3. 从机侧：用本工程的 **设备(从机)模式** 或串口/示波器抓包，统计收到字节数。
4. 两边字节数应完全相等；本工程从机模式的比对方式：接收完成后把 `tvRecv` 内容与源文件对比，
   或直接看 `RX: xxx KB` 是否等于发送量。
5. 反向验证：从机连续 notify，主机 `ReadDialogFragment` 打开通知，
   “接收”字节数应持续增长且不卡顿（显示缓存超 64KB 会滚动截断，属预期）。

---

## 六、构建说明

> 以下三种任选一种就能拿到 APK（工程本體不依赖任何私模文件）。

### 方式 A：电脑上的 Android Studio（最省事）

1. File → Open → 选 `BleDebugAssistant` 目录；
2. 等 Sync 完成（依赖走阿里云镜像，无梯子也能下）；
3. 菜单 Build → Build Bundle(s)/APK(s) → Build APK(s)，
   产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### 方式 B：命令行

```bash
gradle :app:assembleDebug          # 需本地有 JDK 17 + Android SDK
# 或先 gradle wrapper 生成 gradlew，再 ./gradlew :app:assembleDebug
```

### 方式 C：手机/无环境也能出包（GitHub Actions）

已自带 `.github/workflows/build-apk.yml`：把本目录推到一个 GitHub 仓库，
Actions 会自动构建，在运行的 Artifacts 里直接下载 `app-debug.apk`，
不需要本地安装任何 SDK。

### 首次构建可能遇到的问题

| 现象 | 处理 |
|---|---|
| `Could not find com.clj.fastble:FastBleLib:2.3.4` | 仓库镜像没命中 → 按上一節“FastBle 依赖”方式把 `FastBleLib` 源码作为 module 引入 |
| `BleGattCallback` / `BleWriteCallback` 报未实现的抽象方法 | FastBle 版本差异，按上一節修改 **BleController.java 一个文件** |
| `Namespace not specified`（引入 FastBleLib 源码时） | 在 `FastBleLib/build.gradle` 的 `android{}` 里加 `namespace 'com.clj.fastble'`，并删掉其 AndroidManifest 的 `package` 属性 |


```bash
# 1) 直接用 Android Studio 打开本目录（AGP 8.2.2 / compileSdk 34 / minSdk 23）
#    首次 Sync 会自动下载 Gradle 8.4（已提供 gradle/wrapper/gradle-wrapper.properties）
# 2) 命令行：在 Android Studio 里 Sync 一次后，或自己执行 gradle wrapper 生成 gradlew，然后：
#    ./gradlew :app:assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk
```

### FastBle 依赖（默认已内置兼容实现，云编译必定成功）

FastBleLib 2.3.4 只发布在已关停的 jcenter，云编译很容易因拉不到包而失败。
所以工程里带了一份 **API 完全一致的本地实现**：

```
app/src/main/java/com/fastble/bledebugassistant/blelib/BleManager.java
```

它提供了 FastBle 同名同签名的类型与方法（`BleManager` / `BleScanRuleConfig` /
`BleDevice` / `ScanRecord` / `BleWriteEntity` / `BleException` /
`BleGattCallback` / `BleScanCallback` / `BleWriteCallback` / `BleReadCallback` /
`BleNotifyCallback`），内部用原生 `BluetoothLeScanner` + `BluetoothGatt` 实现，
行为与 FastBle 一致（扫描回调在主线程、GATT 回调在 BLE 线程、单写操作串行）。

→ 这样整个工程只依赖 **Google / Maven Central 的 AndroidX**，云编译零风险。

**想换回真正的 FastBleLib（共 3 步）：**

1. `app/build.gradle` 取消注释：`implementation 'com.clj.fastble:FastBleLib:2.3.4'`
2. 删除 `app/src/main/java/com/fastble/bledebugassistant/blelib/` 目录
3. `core/BleController.java` 顶部把
   `import com.fastble.bledebugassistant.blelib.BleManager*;`
   换回 `import com.clj.fastble.*;`

业务代码（`BleTxEngine` / `BleRxEngine` / 所有 UI 与弹窗）一行都不用改。

如果你更愿意以源码方式引入 FastBle：把 https://github.com/Jasonchenlijian/FastBle 的
`FastBleLib` 目录拷到工程根目录，`settings.gradle` 里打开 `include ':FastBleLib'`，
`app/build.gradle` 改成 `implementation project(':FastBleLib')`
（然后在 `FastBleLib/build.gradle` 的 `android{}` 里补 `namespace 'com.clj.fastble'`，
并删掉它 AndroidManifest 的 `package` 属性，以适配 AGP 8）。

### FastBle API 适配点（只改一个文件）

所有 FastBle 调用都集中在 **`core/BleController.java`**，用到的 API：

```java
BleManager.getInstance().init(Application)
BleManager.getInstance().enableLog(boolean)
BleManager.getInstance().isBlueEnable()
BleManager.getInstance().initScanRule(BleScanRuleConfig)   // .setAutoConnect().setScanTimeOut(0)
BleManager.getInstance().scan(BleScanCallback)             // onScanStarted/onScanning/onScanFinished
BleManager.getInstance().cancelScan()
BleManager.getInstance().connect(String mac, BleGattCallback)
BleManager.getInstance().disconnect(BleDevice)
BleManager.getInstance().write(BluetoothGattCharacteristic, BleWriteEntity, BleWriteCallback)
BleManager.getInstance().read(BluetoothGattCharacteristic, BleReadCallback)
BleManager.getInstance().notify / indicate(BluetoothGattCharacteristic, BleNotifyCallback)
BleManager.getInstance().stopNotify(BluetoothGattCharacteristic)
```

不同 FastBle 版本里 `BleGattCallback` / `BleNotifyCallback` 的方法签名略有差异，
本文件里的回调方法**故意不加 `@Override`** 并且同时实现了
`onConnectFail(BleException)` 与 `onConnectFail(BleDevice, BleException)`、
`onCharacteristicChanged(byte[])` 与 `onNotify(byte[])` 两套名称，
以兼容 2.x 各分支。若你的版本仍有差异，只改这一个文件即可。

**如果你的 FastBle 只有 4 参写接口**（`write(BluetoothGattCharacteristic, byte[], boolean, BleWriteCallback)`），
把 `BleController.write()` 里的三行：

```java
BleWriteEntity entity = new BleWriteEntity(packet, false);
BleManager.getInstance().write(characteristic, entity, new BleWriteCallback() { ... });
```

改成：

```java
BleManager.getInstance().write(characteristic, packet, false, new BleWriteCallback() { ... });
```

业务逻辑（`BleTxEngine` 的串行 + 重传）完全不需要改。

另外：`MTU` / `PHY` / `连接优先级` 都直接作用在 `BluetoothGatt` 上
（`gatt.requestMtu()` / `gatt.setPreferredPhy()` / `gatt.requestConnectionPriority()`），
不依赖 FastBle 的高层封装，MTU 变化结果通过 `BleGattCallback.onMtuChanged` 回写。

---

## 七、与原版的差异说明

| 项目 | 说明 |
|---|---|
| 从机模式对比文件 | 原版从机模式有“对比文件”功能，本工程实现为「RX 字节统计 + 屏幕数据核对」，未做磁盘逐字节比对 |
| 二维码扫 MAC | 原版用 zxing 扫二维码得到 MAC，本工程未包含（减少依赖） |
| 隐私政策/关于页 | 以弹窗形式提供，未做独立 WebView 页面 |
| 蓝牙库 | 原版是 WCH 私有 BLE 库，本工程全部改用 FastBle（从机模式用原生 API） |
