# optional-poc —— 可选加分：MQTT + FFmpeg 的最小可跑验证

> 目标：证明"设备上报 → 云端指令 → 设备回执"这条链路在**真实 MQTT Broker** 上成立，
> 以及"视频预览"这条链路在**真实推流/拉流**上成立。
> 这两个 PoC 都是**可弃的**：不进入主干代码，只用于验证架构假设与对外承诺（尤其是 `05` 的 200 路预览）。

## 1. 组成

```
optional-poc/
  docker-compose.yml        # EMQX(MQTT) + 一个模拟设备容器 + 一个 FFmpeg 推流容器
  mosquitto/config.conf     # Broker 配置（ACL 示例：按 tenant/project 限制发布与订阅）
  device_simulator.py       # 模拟设备：连 Broker、周期上报、订阅指令、回复回执（带幂等去重）
  stream_push.sh            # 用 FFmpeg 造一路测试视频流并推到 RTMP（验证媒体链路）
  README.md
```

## 2. 一键跑（需要 Docker）

```bash
cd optional-poc
docker compose up -d
# 观察：
docker compose logs -f device-simulator      # 应看到周期性 telemetry 与 ack
# 验证指令链路（用 EMQX 自带的 WebSocket/CLI 或 mosquitto_pub 发一条指令）
mosquitto_pub -h localhost -t 'down/t1/p1/dev-001/command' \
  -m '{"schema":"command/v1","command_id":"cmd-001","action":"reboot","deadline_ms":5000}'
```

预期：`device-simulator` 打印 `ack command_id=cmd-001`，并输出 `{"status":"SUCCESS"}`——
**注意回执里必须带原 `command_id`**，这是 `02` 里"受理 ≠ 成功"的最小实现。

## 3. 视频链路验证

```bash
cd optional-poc
./stream_push.sh            # ffmpeg 生成测试图并推到 rtmp://localhost:1935/live/test
# 拉流验证
ffprobe -rtsp_transport tcp rtmp://localhost:1935/live/test
```

**这个 PoC 想证明的三件事**（对应 `02` 的 2.B 取舍）：
1. 媒体面与业务面可以**完全解耦部署**：FFmpeg/媒体进程不依赖业务进程存活；
2. 首帧时间与并发路数是**可测量的**（不是承诺，是先测再承诺）；
3. 弱网/断流场景下**自动重连与降级**（推流中断后拉流侧应重试并最终恢复，而不是黑屏）。

## 4. 与主干的边界

- 本目录**不参与**主干构建与 CI（`coding/` 的编译只扫 `coding/src`）。
- 若 PoC 验证成功，产出物是**结论与参数**（如"单机 FFmpeg 稳定转 40 路 720p"），
  而不是把 PoC 代码洗进主干——这与 `03` 的"回流只回流机制"是同一条原则。
