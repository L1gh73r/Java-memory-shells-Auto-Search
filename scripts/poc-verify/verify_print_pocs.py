#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_print_pocs.py

针对 JSON 文件中“只打印标识、不做 RCE” 的 POC 进行自动验证：
- 使用 SimplePocController 的 /api/verify/inject 动态编译执行 reflection_code
- 不再依赖内存马和 RCE 触发，只判断控制台是否打印预期的 *_success 标识
- 默认通过 `mvn spring-boot:run` 启动应用

容器约定：
- Spring Boot 工程通过卷挂载到 /app，或由环境变量 PROJECT_ROOT 指定
- POC JSON 文件通过卷挂载到 /verify/file5.json，或由参数 --poc-file 指定
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Tuple

import requests


BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
HEALTH_URL = f"{BASE_URL}/api/verify/health"
INJECT_URL = f"{BASE_URL}/api/verify/inject"

# Spring Boot 工程根目录：默认使用环境变量 PROJECT_ROOT，否则使用脚本所在目录
PROJECT_ROOT = Path(os.environ.get("PROJECT_ROOT", Path(__file__).resolve().parent))

DEFAULT_POC_FILE = PROJECT_ROOT / "file (5).json"
DEFAULT_LOG_PATH = PROJECT_ROOT / "server_logs" / "print_pocs.log"

STARTUP_TIMEOUT = 120
STARTUP_INTERVAL = 2

TRIGGER_ENDPOINTS = [
    f"{BASE_URL}/hello",
    f"{BASE_URL}/user",
]


def wait_for_spring(timeout: int = STARTUP_TIMEOUT,
                    interval: int = STARTUP_INTERVAL) -> bool:
    """轮询 /api/verify/health，直到 Spring 完全启动或超时。"""
    print(f"[*] Waiting for Spring Boot to be ready at {HEALTH_URL} ...")
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = requests.get(HEALTH_URL, timeout=5)
            if resp.status_code == 200:
                try:
                    data = resp.json()
                    status = data.get("status") or data.get("Status") or "UNKNOWN"
                    print(f"[+] Spring health check OK, status={status}")
                except Exception:
                    print("[+] Spring health check OK (non-JSON response)")
                return True
        except Exception as e:
            print(f"[-] Health check failed: {e}")
        print(f"[*] Not ready yet, sleep {interval}s ...")
        time.sleep(interval)
    print("[!] Spring Boot did not become ready within timeout")
    return False


def start_server_with_mvn(log_path: Path) -> Tuple[subprocess.Popen, Any]:
    """使用 `mvn spring-boot:run` 启动应用，日志重定向到指定文件。"""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_file = open(log_path, "w", encoding="utf-8")
    print(f"[*] Starting Spring Boot with: mvn spring-boot:run (log: {log_path})")
    proc = subprocess.Popen(
        ["mvn", "spring-boot:run"],
        cwd=str(PROJECT_ROOT),
        stdout=log_file,
        stderr=subprocess.STDOUT,
    )
    return proc, log_file


def stop_server(proc: subprocess.Popen, log_file):
    if proc is not None:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
    if log_file is not None and not log_file.closed:
        log_file.close()


def load_pocs(path: Path) -> List[Dict[str, Any]]:
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"[!] Failed to load {path}: {e}")
        sys.exit(1)


def inject_reflection_code(poc: Dict[str, Any]) -> Tuple[bool, str]:
    """调用 /api/verify/inject 动态编译并执行 reflection_code。"""
    poc_id = poc.get("id")
    reflection_code = poc.get("reflection_code") or ""
    if not reflection_code.strip():
        return False, "Empty reflection_code"

    payload = {
        "id": str(poc_id),
        "reflection_code": reflection_code,
    }
    try:
        resp = requests.post(INJECT_URL, json=payload, timeout=60)
        try:
            data = resp.json()
        except Exception:
            return False, f"Non-JSON response (HTTP {resp.status_code}): {resp.text[:200]}"
        if data.get("success"):
            return True, data.get("message", "OK")
        msg = data.get("message") or "Unknown error"
        err = data.get("error")
        if err:
            msg = f"{msg} | error: {str(err)[:200]}"
        return False, msg
    except Exception as e:
        return False, f"Request failed: {e}"


def extract_marker(poc: Dict[str, Any]) -> str:
    """
    尝试从 reflection_code 或 info 中提取形如 Xxx_yyy_success 的标识字符串。
    优先：reflection_code 中的字符串字面量；否则从 info 文本中提取。
    """
    rc = poc.get("reflection_code") or ""
    info = poc.get("info") or ""
    # 在 reflection_code 里找 "XXXX_success"
    m = re.findall(r'"([A-Za-z0-9_]+_success)"', rc)
    if m:
        return m[0]
    # 在 info 说明中找
    m = re.findall(r'([A-Za-z0-9_]+_success)', info)
    if m:
        return m[0]
    return ""


def read_new_logs(log_path: Path, offset: int) -> str:
    """从日志文件指定 offset 开始读取新增内容。"""
    try:
        with log_path.open("r", encoding="utf-8", errors="ignore") as f:
            f.seek(offset)
            return f.read()
    except FileNotFoundError:
        return ""


def trigger_application(marker: str):
    """
    在注入后简单访问几个 HTTP 接口，尽量触发挂载点执行。
    即便不精确匹配每个 POC 的真实调用路径，至少可以激活大部分 Web 相关逻辑。
    """
    session = requests.Session()
    for url in TRIGGER_ENDPOINTS:
        try:
            resp = session.get(url, timeout=5)
            print(f"    [trigger] GET {url} -> {resp.status_code}")
        except Exception as e:
            print(f"    [trigger] GET {url} failed: {e}")
    # 略微等待日志 flush
    time.sleep(1.0)


def print_poc_header(poc: Dict[str, Any]):
    poc_id = poc.get("id")
    poc_name = poc.get("class_name", "Unknown")
    print("\n" + "=" * 60)
    print(f"[POC {poc_id}] {poc_name}")
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(description="Verify print-only POCs using JSON file")
    parser.add_argument(
        "--poc-file",
        default=str(DEFAULT_POC_FILE),
        help="Path to POC JSON file (default: file (5).json in project root)",
    )
    parser.add_argument(
        "--poc-ids",
        default="",
        help="Comma-separated list of POC IDs to run (default: all)",
    )
    parser.add_argument(
        "--log-path",
        default=str(DEFAULT_LOG_PATH),
        help="Path to Spring Boot log file for this verification run",
    )
    args = parser.parse_args()

    poc_file = Path(args.poc_file).resolve()
    log_path = Path(args.log_path).resolve()

    print(f"[*] Base URL: {BASE_URL}")
    print(f"[*] Health URL: {HEALTH_URL}")
    print(f"[*] Inject URL: {INJECT_URL}")
    print(f"[*] Project root: {PROJECT_ROOT}")
    print(f"[*] POC file: {poc_file}")
    print(f"[*] Log file: {log_path}")

    pocs = load_pocs(poc_file)
    if args.poc_ids.strip():
        wanted = {pid.strip() for pid in args.poc_ids.split(",") if pid.strip()}
        pocs = [p for p in pocs if str(p.get("id")) in wanted]
    print(f"[*] Loaded {len(pocs)} POCs\n")

    proc = None
    log_file = None
    total = 0
    injected_ok = 0
    log_hit = 0

    try:
        proc, log_file = start_server_with_mvn(log_path)
        if not wait_for_spring():
            print("[!] Abort: Spring Boot not ready.")
            return

        # 获取当前日志大小，后续每个 POC 只检查新增部分
        try:
            offset = log_path.stat().st_size
        except FileNotFoundError:
            offset = 0

        for poc in pocs:
            print_poc_header(poc)
            total += 1

            marker = extract_marker(poc)
            if not marker:
                print("  [!] No *_success marker found in reflection_code/info; skip log verification")

            # 记录此 POC 之前的日志 offset
            try:
                offset = log_path.stat().st_size
            except FileNotFoundError:
                offset = 0

            ok, msg = inject_reflection_code(poc)
            if not ok:
                print(f"  ❌ Injection FAILED: {msg}")
                continue

            print(f"  ✅ Injection SUCCESS: {msg}")
            injected_ok += 1

            # 触发应用逻辑，让潜在的挂载点执行
            trigger_application(marker)

            if marker:
                new_logs = read_new_logs(log_path, offset)
                if marker in new_logs:
                    print(f"  ✅ MARKER FOUND in logs: {marker}")
                    log_hit += 1
                else:
                    print(f"  ⚠️  MARKER NOT FOUND in logs: {marker}")
            else:
                print("  [!] Skipped marker verification (no marker)")

    finally:
        stop_server(proc, log_file)

    print("\n" + "=" * 60)
    print("PRINT POC VERIFICATION SUMMARY")
    print("=" * 60)
    print(f"Total POCs:        {total}")
    print(f"Injected OK:       {injected_ok}")
    print(f"Markers detected:  {log_hit}")
    print(f"Markers missing:   {injected_ok - log_hit}")
    print("=" * 60 + "\n")


if __name__ == "__main__":
    main()


