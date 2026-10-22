#!/usr/bin/env python3
"""模拟设备：周期上报遥测、订阅指令、回复回执（按 command_id 去重，模拟设备侧幂等）。

需要本机有 paho-mqtt；本 PoC 用 docker compose 跑纯 python 镜像，
若缺依赖可执行: pip install paho-mqtt
"""
import json
import os
import time

try:
    import paho.mqtt.client as mqtt
except ImportError:  # pragma: no cover
    raise SystemExit("需要 paho-mqtt：pip install paho-mqtt")

TENANT = os.environ.get("TENANT", "t1")
PROJECT = os.environ.get("PROJECT", "p1")
DEVICE = os.environ.get("DEVICE", "dev-001")
BROKER = os.environ.get("BROKER", "emqx")
SEEN = set()  # 模拟设备侧幂等：已处理的 command_id 不再重复执行

UP = f"up/{TENANT}/{PROJECT}/{DEVICE}/telemetry"
ACK = f"up/{TENANT}/{PROJECT}/{DEVICE}/ack"
DOWN = f"down/{TENANT}/{PROJECT}/{DEVICE}/command"


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode())
    except Exception:
        print("非法载荷，丢弃:", msg.payload)
        return
    command_id = payload.get("command_id")
    if command_id in SEEN:
        print("重复指令，已忽略(设备侧幂等):", command_id)
        return
    SEEN.add(command_id)
    print("执行指令:", command_id, payload.get("action"))
    ack = {"command_id": command_id, "status": "SUCCESS", "ts": int(time.time() * 1000)}
    client.publish(ACK, json.dumps(ack), qos=1)


def main():
    client = mqtt.Client(client_id=f"{TENANT}-{PROJECT}-{DEVICE}")
    client.on_connect = lambda c, u, f, rc: c.subscribe(DOWN, qos=1)
    client.on_message = on_message
    client.connect(BROKER, 1883, 30)
    client.loop_start()
    print("设备已上线:", DEVICE)
    try:
        while True:
            telemetry = {
                "schema": "telemetry/v1",
                "ts": int(time.time() * 1000),
                "tenant_id": TENANT,
                "project_id": PROJECT,
                "device_id": DEVICE,
                "metrics": {"cpu": 11.2, "temp": 45},
                "status": {"online": True, "firmware": "3.2.1"},
            }
            client.publish(UP, json.dumps(telemetry), qos=0)
            time.sleep(10)
    except KeyboardInterrupt:
        pass
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
