# 欢律蓝牙协议反编译笔记

本文记录通过 JADX 查看欢律/HeyMelody 官方 App 后，对 OPPO 耳机蓝牙连接与 SPP 协议的整理。类名多为混淆名，建议把这里当成定位线索和实现依据，而不是完整协议规范。

## 结论

- 欢律经典蓝牙数据连接使用 `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)`，不是直接写死公开 channel。
- 已确认两个 OPPO/HeyMelody SPP UUID：
  - `00001107-D102-11E1-9B23-00025B00A5A5`
  - `0000079A-D102-11E1-9B23-00025B00A5A5`
- `UUID` 本身不是 channel；Android 会通过 SDP 解析到实际 RFCOMM server channel，再建立 socket。公共 API 不直接暴露这个 channel。
- 欢律代码里有一层内层 packet：`Cmd(2 LE) + Seq/Type(1) + Len(2 LE) + Payload`。OppoPods 当前发送的是耳机 socket 上的完整外层帧：`AA + TotalLen + 00 00 + 内层 packet`。
- 命令响应一般用 `cmd | 0x8000` 表示，例如请求 `0x0106` 的响应是 `0x8106`。

## JADX 证据

### RFCOMM UUID 连接

`p553p6.AbstractC6302a`，注释名为 `BaseBRConnection.java`。连接创建点：

```java
bluetoothDevice.createRfcommSocketToServiceRecord(c6188a.f23639d);
```

同一类的 `p540o6.C6188a`，注释名为 `BRClientConnection.java`，负责 socket connect、读写线程、超时关闭等：

```java
bluetoothSocket.connect();
bluetoothSocket.getOutputStream().write(...);
```

`p553p6.AbstractC6303b`，注释名为 `BaseBRDevice.java`，静态字段里确认经典蓝牙默认 UUID：

```java
f23666w = UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5");
```

`p623v7.C6745e`，注释名为 `WhitelistRepositoryServerImpl.kt`，白名单默认 UUID 列表：

```java
["0000079A-D102-11E1-9B23-00025B00A5A5",
 "00001107-D102-11E1-9B23-00025B00A5A5"]
```

`p529n6.C6125f`，注释名为 `GattDevice.java`，BLE/GATT 侧也使用 `0000079A...` 作为 service UUID，并派生出：

- `0100079A-D102-11E1-9B23-00025B00A5A5`
- `0200079A-D102-11E1-9B23-00025B00A5A5`

这说明 `0000079A...` 不只是随便的字符串，而是欢律蓝牙协议族的一部分。

### Packet 结构

`p362b6.C2740a`，注释名为 `Packet.java`，从 byte array 解析出：

```text
offset 0..1: cmd, little endian
offset 2:    seq/type
offset 3..4: payload length, little endian
offset 5..:  payload
```

解析出的 packet key：

```java
((seqOrType & 0xff) << 16) | (cmd & 0x7fff)
```

这和 OppoPods 当前外层格式对应：

```text
AA [TotalLen] 00 00 [Cmd 2B LE] [Seq] [PayLen 2B LE] [Payload...]
```

也就是说，欢律 `Packet.java` 看到的是去掉 `AA TotalLen 00 00` 后的内层 packet。

`p362b6.C2741b`，注释名为 `PacketFactory.java`：

- `m5621a(address, cmd, payload)` 会给每个地址维护递增 seq。
- `m5620b(packet, payload)` 会生成响应包，命令位设置为 `cmd | 0x8000`。

### 命令线索

`com.oplus.melody.btsdk.protocol.commands.C4048c`，注释名为 `PollCommandManager.java`，包含大量查询命令：

| 十进制 | 十六进制 | 反编译语义 |
|---:|---:|---|
| 256 | `0x0100` | getRemoteCapability |
| 257 | `0x0101` | getRemoteMTU |
| 258 | `0x0102` | getRemoteVID |
| 259 | `0x0103` | getRemotePID |
| 261 | `0x0105` | getRemoteVersion |
| 262 | `0x0106` | getBatteryLevel |
| 268 | `0x010C` | noise reduction mode queries |
| 269 | `0x010D` | getFeatureSwitchStatus |
| 271 | `0x010F` | getCurrentEqualizerMode |
| 276 | `0x0114` | getCurrentCodecType |
| 280 | `0x0118` | getEarRestoreData |
| 284 | `0x011C` | getTriangleInfo |
| 286 | `0x011E` | getEarScanData |
| 289 | `0x0121` | getEarToneData |
| 290 | `0x0122` | getAllEqInfo |
| 293 | `0x0125` | getAccountKey |
| 298 | `0x012A` | getHeadsetSpatialType |
| 299 | `0x012B` | getGameSoundInfo |
| 302 | `0x012E` | getAISummaryType |
| 303 | `0x012F` | multi SPP command info |

OppoPods 当前已经使用或解析的命令：

| 功能 | Cmd | 说明 |
|---|---:|---|
| 电量查询 | `0x0106` | 空 payload |
| 电量响应 | `0x8106` | `[Index, RawValue]` pairs |
| 降噪查询 | `0x010C` | payload `01 01` |
| 降噪响应 | `0x810C` | payload 内扫描 `01 01 <val1> <val2>` |
| 主动上报 | `0x0204` | battery/ANC/button 等事件复用 |
| 批量状态查询 | `0x010D` | 目前用于查询 game mode |
| 批量状态响应 | `0x810D` | 找 key `0x28` 读取 game mode |
| 设置游戏模式 | `0x0403` | `28 01` 开，`28 00` 关 |
| 设置 ANC | `0x0404` | `01 01 <mode>` |

## OppoPods 当前落地

当前实现位于：

- `app/src/main/java/moe/chenxy/oppopods/pods/OppoRfcommSocketFactory.kt`
- `app/src/main/java/moe/chenxy/oppopods/pods/RfcommConnectionMethod.kt`
- `app/src/main/java/moe/chenxy/oppopods/pods/Packets.kt`

设置页现在有两种连接方式：

- `UUID`：尝试 `00001107...` 和 `0000079A...`。
- `Channel 15`：直接反射调用 `createRfcommSocket(15)`，用于和旧实现对照测试。

日志会显示当前实际走的路径：

```text
RFCOMM connection method: uuid
RFCOMM connected via UUID 00001107-D102-11E1-9B23-00025B00A5A5

RFCOMM connection method: channel
RFCOMM connected via channel 15
```

查看方式：

```powershell
adb logcat -s OppoPods-RfcommController OppoPods-AppRfcomm
```

## 仍需实机验证

- UUID 经 SDP 最终解析出的实际 RFCOMM channel 需要 HCI snoop 或反射读取 `BluetoothSocket` 内部字段确认。
- 欢律 packet 内层和 OppoPods 当前 `AA` 外层之间的拆包/封包位置还可以继续深挖，尤其是 read loop 中对原始流的切包逻辑。
- `0x0204` 主动上报复用了多种事件，当前只处理了电量和 ANC，按钮事件还可以继续解析。
- `0x010D/0x810D` 的 key-value payload 还有更多 feature key，`0x28` 只是当前 game mode 相关的一项。
