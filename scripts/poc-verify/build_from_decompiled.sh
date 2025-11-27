#!/usr/bin/env bash
set -euo pipefail

echo "== Build JAR from decompiled sources =="

DECOMP_INPUT_DIR="${DECOMP_INPUT_DIR:-/input-decompiled}"
OUTPUT_DIR="${OUTPUT_DIR:-/output}"
TARGET_JAR_NAME="${TARGET_JAR_NAME:-rebuilt-app.jar}"

echo "[*] DECOMP_INPUT_DIR = ${DECOMP_INPUT_DIR}"
echo "[*] OUTPUT_DIR       = ${OUTPUT_DIR}"
echo "[*] TARGET_JAR_NAME  = ${TARGET_JAR_NAME}"

if [[ ! -d "${DECOMP_INPUT_DIR}" ]]; then
  echo "[!] 反编译源码目录不存在: ${DECOMP_INPUT_DIR}" >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"

echo "[1/4] 拷贝反编译结果到工作目录 /app ..."
rm -rf /app/*
cp -a "${DECOMP_INPUT_DIR}/." /app/

cd /app

if [[ ! -f "pom.xml" ]]; then
  echo "[!] /app 下未找到 pom.xml，无法使用 Maven 构建。" >&2
  exit 1
fi

echo "[2/4] 调整源码目录结构（main -> src/main/java）..."
if [[ -d "main" && ! -d "src/main/java" ]]; then
  mkdir -p src/main
  mv main src/main/java
fi

echo "[3/4] 使用 Maven 构建 JAR（忽略测试）..."
mvn -q -DskipTests package

echo "[4/4] 将生成的 JAR 复制到输出目录..."
BUILT_JAR=""
if ls target/*.jar >/dev/null 2>&1; then
  # 选择第一个构建产物
  BUILT_JAR=$(ls target/*.jar | head -n 1)
else
  echo "[!] 未在 target/ 下找到任何 JAR 文件。" >&2
  exit 1
fi

cp "${BUILT_JAR}" "${OUTPUT_DIR}/${TARGET_JAR_NAME}"

echo "== Build finished. Output JAR: ${OUTPUT_DIR}/${TARGET_JAR_NAME} =="


