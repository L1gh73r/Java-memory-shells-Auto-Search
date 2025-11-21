#!/bin/bash

set -e

echo "========================================"
echo "Starting Showmaker Pipeline"
echo "========================================"

# 1. Clean up previous results
echo "[1/5] Cleaning up previous results..."
rm -rf SAST/codeql-output/*
rm -rf jos_result/*
mkdir -p SAST/codeql-output
mkdir -p jos_result

# 2. Run CodeQL Analysis
echo "[2/5] Running CodeQL Analysis..."
# 始终使用最新的 codeql-runner 镜像，先构建再运行
docker-compose build codeql-runner
docker-compose run --rm codeql-runner
echo "CodeQL Analysis Complete."

# 3. Run Agent Pre-Analysis (基于 CSV 的 hook 日志预分析)
echo "[3/5] Running Agent Pre-Analysis (hook logs)..."
# 确保使用最新的预分析镜像
docker-compose build agent-pre-analyzer
docker-compose run --rm agent-pre-analyzer
echo "Agent Pre-Analysis Complete."

# 4. Run JOSE Memory Search (按 CSV 批量构造断点并搜索内存马)
echo "[4/5] Running JOSE Memory Search (batch)..."
# 确保使用最新的内存搜索镜像
docker-compose build memory-search-runner
docker-compose run --rm memory-search-runner
echo "JOSE Memory Search Complete."

# 5. Start Workflow Hub
echo "[5/5] Starting Workflow Hub..."
# 确保使用最新的 workflow 镜像
docker-compose build workflow
docker-compose up -d workflow

echo "========================================"
echo "Pipeline Started Successfully!"
echo "Workflow Hub should be available at http://localhost:7781"
echo "========================================"
