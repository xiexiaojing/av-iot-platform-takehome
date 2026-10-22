# coding/ —— 最小可运行实现（Java）

**主语言：Java**（源码兼容 Java 17+，在 JDK 26 上验证通过）。
只用 JDK 自带能力（`com.sun.net.httpserver` + `java.net.http`），**不依赖 Maven/Gradle/任何三方库**，
因此在没有外网、没有构建工具的机器上也能编译运行。

## 1. 目录

```
coding/
  build.sh                 # 编译脚本（javac）
  src/c2d/
    CommandService.java    # 核心：幂等 / 状态机 / 超时兜底 / 并发安全
    Command.java           # 指令不可变快照 + 状态常量
    Device.java            # 设备在线状态
    Json.java              # 极简 JSON 读写（扁平对象）
    ServerMain.java        # HTTP 服务入口
    CliMain.java           # 命令行客户端
    ServiceTest.java       # 自动化测试（自制断言，无外部框架）
  scripts/
    smoke.sh               # 冒烟：上线 -> 下发 -> 查终态 -> 幂等回放 -> 离线失败
    load_demo.py           # 并发幂等验证（Python）
```

## 2. 编译与运行

```bash
cd coding

# 编译
./build.sh                       # 等价于 javac -encoding UTF-8 -Xlint:all -d build/classes $(find src -name '*.java')

# 跑单元测试（16 个断言）
java -cp build/classes c2d.ServiceTest
# 期望输出结尾： "16 个断言, 0 个失败"，退出码 0

# 启动服务
java -cp build/classes c2d.ServerMain --host 127.0.0.1 --port 8080 --ack-ms 50
# --ack-ms 是"模拟设备回执延迟"，用于演示 PENDING -> SUCCESS 的过程

# 命令行客户端
java -cp build/classes c2d.CliMain device online dev-1
java -cp build/classes c2d.CliMain cmd dispatch dev-1 reboot --idem key-1 --timeout-ms 3000
java -cp build/classes c2d.CliMain cmd status <command_id>
```

## 3. 一键验证

```bash
./scripts/smoke.sh
# 依次校验：设备上线 / 下发拿到 command_id / 终态 SUCCESS / 同幂等键返回同一 command_id / 离线设备 FAILED+DEVICE_OFFLINE
# 任何一步失败 exit 1
```

并发幂等验证（同一 `idempotency_key` 并发 30 次，唯一 `command_id` 数量必须为 1）：

```bash
java -cp build/classes c2d.ServerMain --port 18080 &
python3 scripts/load_demo.py --url http://127.0.0.1:18080 --count 30
# 期望：唯一 command_id 数量 : 1  /  LOAD DEMO PASSED  / 退出码 0
```

## 4. 接口说明

| 方法 | 路径 | 说明 | 成功响应 |
|------|------|------|---------|
| GET | `/healthz` | 健康检查 | `200 {"status":"ok"}` |
| PUT | `/devices/{device_id}/online` | 设置设备在线（body `{"online":true}`） | `200` |
| GET | `/devices/{device_id}` | 查询设备状态 | `200` |
| POST | `/commands` | 下发指令 | `202` + `command_id` + 当前 `status` |
| GET | `/commands/{command_id}` | 查询指令状态 | `200` + `status/error/error_message` |

POST `/commands` 请求体：

```json
{"device_id":"dev-1","action":"reboot","idempotency_key":"key-1","timeout_ms":3000}
```

响应体（受理 ≠ 成功：这里返回的是**受理结果与当前状态**，终态需轮询）：

```json
{"command_id":"cmd_cfe4d18ca208dd5b","device_id":"dev-1","action":"reboot",
 "status":"PENDING","error":"","error_message":"","idempotent_replay":false,
 "created_ms":1792637925946,"updated_ms":1792637925946,"deadline_ms":1792637928946}
```

## 5. 关键实现说明（面试会被追问的三点）

### 5.1 幂等：为什么不用"先查后写"

```java
CompletableFuture<Command> future = idempotency.computeIfAbsent(idempotencyKey, key -> {
    createdHere.set(true);              // 只有"创建者"线程会执行这段映射函数
    CompletableFuture<Command> created = new CompletableFuture<>();
    created.complete(createNewCommand(deviceId, action, key, fingerprint, timeout));
    return created;
});
```

- `ConcurrentHashMap.computeIfAbsent` 对同一个 key **保证只执行一次映射函数**（内部加锁），
  因此"同键只创建一条指令"是**原子的**，不存在 `containsKey` + `put` 之间的竞态窗口。
- 其余并发请求拿到的是**同一个 Future**，直接 `join()` 到同一条指令，天然完成"幂等回放"。
- `command_id` 由幂等键 **SHA-256 前 16 位十六进制确定性派生**（`makeCommandId`），
  即使 Future 机制失效，id 也不会变——**双保险**。
- `createdHere` 标志用来区分"本次创建"与"幂等回放"（映射函数只在创建者线程执行）。
- **同键不同参数**（device/action 不同）→ 返回 `409 idempotency_conflict`，不静默复用。

### 5.2 失败语义：设备离线为什么不是"稍后重发"

需求把"设备离线"当成**明确失败**而不是待办：政企场景里"平台说成功、设备没动"是信任级事故。
因此 `dispatch` 在设备离线时直接生成 `FAILED/DEVICE_OFFLINE` 的指令（而不是丢进队列等上线）。
**如果产品需要"上线后补发"**，正确做法是显式的另一条业务语义（例如 `PENDING_OFFLINE` + 明确的补发策略与超时），
而不是把 `SENT` 这种半状态混进成功路径——这一点在 `06` 的审阅表问题 3、4 中有对应案例。

### 5.3 超时与并发

- **超时兜底**：`CommandService` 内部有一个 daemon 调度线程每 25ms 扫一遍 `PENDING` 的指令：
  `now >= deadline` → `FAILED/TIMEOUT`；`now >= readyMs` 且设备在线 → `SUCCESS`；
  `readyMs` 已到但设备掉线 → `FAILED/DEVICE_OFFLINE`。
  **超时判定优先于回执判定**（宁可失败也不误报成功）。
- **状态流转用整体替换（CAS 语义）**：`Command` 是 `record`（不可变），流转时
  `commands.compute(id, (k, existing) -> existing.status()==PENDING ? newCommand : existing)`，
  避免共享可变字段的可见性问题，也不会出现"超时线程和回执线程互相覆盖"。
- **关闭**：`CommandService implements AutoCloseable`，`close()` 关闭调度线程（daemon 线程不会阻止 JVM 退出）。

## 6. 已知边界（诚实说明）

- 状态**只在内存**，进程重启即丢失；真实实现应把指令表落库（见 `02` 的 A.6：PostgreSQL 分区表 + 冷数据归档）。
- 设备"执行"是**模拟**的（按 `--ack-ms` 延迟后回执），没有真实协议交互；
  真实实现中回执由 MQTT 的 `up/.../ack` 主题驱动（见 `02` 的 A.3）。
- 单进程内存实现，未做多实例下的分布式幂等（真实实现用数据库唯一索引或 Redis 落地幂等键）。
