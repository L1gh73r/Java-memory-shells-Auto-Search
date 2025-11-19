#!/bin/bash

set -e

echo "========================================"
echo "Starting Showmaker Pipeline"
echo "========================================"

# 1. Clean up previous results
echo "[1/3] Cleaning up previous results..."
rm -rf SAST/codeql-output/*
rm -rf jos_result/*
mkdir -p SAST/codeql-output
mkdir -p jos_result

# 2. Run CodeQL Analysis
echo "[2/3] Running CodeQL Analysis..."
# 始终使用最新的 codeql-runner 镜像，先构建再运行
docker-compose build codeql-runner
docker-compose run --rm codeql-runner
echo "CodeQL Analysis Complete."

# 3. Run Agent Pre-Analysis
echo "[3/3] Running Agent Pre-Analysis..."
docker-compose run --rm agent-pre-analyzer
echo "Agent Pre-Analysis Complete."

# 4. Start Workflow Hub
echo "[4/4] Starting Workflow Hub..."
docker-compose up -d workflow

echo "========================================"
echo "Pipeline Started Successfully!"
echo "Workflow Hub should be available at http://localhost:7781"
echo "========================================"
