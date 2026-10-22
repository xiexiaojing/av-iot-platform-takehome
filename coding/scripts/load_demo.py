#!/usr/bin/env python3
"""并发/重复调用同一 idempotency_key，验证唯一 command_id 数量为 1。

用法:
  python3 scripts/load_demo.py --url http://127.0.0.1:18080 --count 30
退出码: 0 通过 / 1 不通过
"""
import argparse
import json
import sys
import threading
import urllib.error
import urllib.request
from collections import Counter


def post_command(url, payload, timeout=10):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:18080")
    ap.add_argument("--device", default="dev-load-1")
    ap.add_argument("--action", default="reboot")
    ap.add_argument("--key", default="load-demo-idem-key")
    ap.add_argument("--count", type=int, default=20)
    args = ap.parse_args()

    commands_url = args.url.rstrip("/") + "/commands"
    results = []
    errors = []
    lock = threading.Lock()

    def worker():
        status, body = post_command(commands_url, {
            "device_id": args.device,
            "action": args.action,
            "idempotency_key": args.key,
            "timeout_ms": 5000,
        })
        with lock:
            results.append((status, body))
            if status != 202:
                errors.append((status, body))

    # 先把设备置为在线（在线状态属于设备管理面，不属于幂等语义面）。
    device_url = args.url.rstrip("/") + "/devices/" + args.device + "/online"
    req = urllib.request.Request(device_url, data=b'{"online":true}',
                                 headers={"Content-Type": "application/json"}, method="PUT")
    try:
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] 无法设置设备在线: {exc}", file=sys.stderr)
        return 1

    threads = [threading.Thread(target=worker) for _ in range(max(args.count, 20))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ids = [body.get("command_id") for _, body in results if body.get("command_id")]
    unique = set(ids)
    counts = Counter(ids)

    print(f"并发/重复调用次数: {len(threads)}")
    print(f"成功受理次数    : {len(results) - len(errors)}")
    print(f"返回的 command_id 列表: {sorted(unique)}")
    print(f"唯一 command_id 数量 : {len(unique)}")
    print(f"每个 id 的出现次数     : {dict(counts)}")
    if errors:
        print(f"错误响应: {errors[:3]}", file=sys.stderr)

    ok = len(unique) == 1 and len(ids) == len(threads) and not errors
    if ok:
        print("LOAD DEMO PASSED: 同一 idempotency_key 只产生一个 command_id")
        return 0
    print("LOAD DEMO FAILED: 幂等未生效", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
