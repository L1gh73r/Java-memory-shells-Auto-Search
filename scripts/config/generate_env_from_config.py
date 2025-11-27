#!/usr/bin/env python3
"""
从仓库根目录的 showmaker-config.yml 生成 .env 文件，
为 docker-compose 和脚本提供统一的环境变量入口。
"""

import os
import sys


def main() -> None:
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    config_path = os.path.join(repo_root, "showmaker-config.yml")
    env_path = os.path.join(repo_root, ".env")

    if not os.path.exists(config_path):
        print(f"未找到配置文件: {config_path}，跳过生成 .env", file=sys.stderr)
        return

    # 轻量级 YAML 解析：仅支持我们自己定义的简单结构，
    # 避免依赖 PyYAML 之类的三方库。
    section = None
    # 默认使用统一的 shared/inputs 目录
    jar_path = "shared/inputs/benchmark/druid_filter_mem-0.0.1-SNAPSHOT.jar"
    decomp_threads = 8
    db_mode = "source"
    queries = []

    def _strip_comment(line: str) -> str:
        idx = line.find("#")
        return line if idx == -1 else line[:idx]

    with open(config_path, "r", encoding="utf-8") as f:
        for raw in f:
            line = _strip_comment(raw).rstrip("\n")
            if not line.strip():
                continue

            # 顶层 section: target / decompiler / codeql
            if not line.startswith(" "):
                if line.startswith("target:"):
                    section = "target"
                elif line.startswith("decompiler:"):
                    section = "decompiler"
                elif line.startswith("codeql:"):
                    section = "codeql"
                else:
                    section = None
                continue

            # 二级 key 或列表项
            stripped = line.strip()
            if section == "target" and stripped.startswith("jar_path:"):
                # jar_path: shared/inputs/benchmark/xxx.jar
                _, val = stripped.split(":", 1)
                val = val.strip().strip('"').strip("'")
                if val:
                    jar_path = val
            elif section == "decompiler" and stripped.startswith("threads:"):
                _, val = stripped.split(":", 1)
                val = val.strip()
                try:
                    decomp_threads = int(val)
                except ValueError:
                    pass
            elif section == "codeql" and stripped.startswith("db_mode:"):
                _, val = stripped.split(":", 1)
                val = val.strip().strip('"').strip("'")
                if val:
                    db_mode = val
            elif section == "codeql" and stripped.startswith("-"):
                # codeql.queries 下的列表项，例如 "  - test2.ql"
                item = stripped.lstrip("-").strip().strip('"').strip("'")
                if item:
                    queries.append(item)

    jar_name = os.path.basename(jar_path)

    # 相对于 SAST 目录的文件名，逗号分隔
    codeql_queries = ",".join(q for q in queries if q)

    lines = [
        f"TARGET_JAR_PATH={jar_path}",
        f"TARGET_JAR_NAME={jar_name}",
        f"DECOMP_THREADS={decomp_threads}",
        f"CODEQL_DB_MODE={db_mode}",
        f"CODEQL_QUERIES={codeql_queries}",
        "",
    ]

    with open(env_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"已根据 {config_path} 生成环境文件: {env_path}")


if __name__ == "__main__":
    main()


