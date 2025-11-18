#!/usr/bin/env bash
set -euo pipefail

echo "== CodeQL Runner (with JavaDecompiler) =="

JAR_PATH="${1:-}"
OUTPUT_DIR="${2:-/opt/output}"
# 第三个参数或环境变量控制数据库构建模式：source（默认）或 build
MODE="${3:-${CODEQL_DB_MODE:-source}}"

if [[ -z "${JAR_PATH}" ]]; then
  echo "用法: codeql-runner <path-to-jar-inside-container> [output-dir]" >&2
  echo "示例: docker run ... codeql-runner /input/app.jar /output" >&2
  exit 1
fi

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "错误: 找不到 JAR 文件: ${JAR_PATH}" >&2
  exit 1
fi

if [[ "${MODE}" != "source" && "${MODE}" != "build" ]]; then
  echo "错误: 无效的构建模式: ${MODE}，仅支持 source 或 build" >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"

WORK_DIR="/opt/work"
DECOMP_DIR="${WORK_DIR}/decompiled-with-deps"

# 数据库根目录（挂载到宿主机以便复用）
DB_ROOT="/opt/databases"
mkdir -p "${DB_ROOT}"

APP_NAME="$(basename "${JAR_PATH}")"
APP_NAME="${APP_NAME%.jar}"
DB_DIR="${DB_ROOT}/${MODE}-${APP_NAME}"

if [[ -f "${DB_DIR}/codeql-database.yml" ]]; then
  echo "[1/5] 检测到已存在的 CodeQL 数据库: ${DB_DIR}，跳过数据库构建。"
else
  echo "[1/5] 使用 JavaDecompiler 反编译 JAR（包含依赖）到: ${DECOMP_DIR}"
  rm -rf "${WORK_DIR}"
  mkdir -p "${DECOMP_DIR}" "${OUTPUT_DIR}"

  java -jar /opt/tools/JavaDecompiler.jar \
    --input "${JAR_PATH}" \
    --output "${DECOMP_DIR}" \
    --threads 8 \
    --include-deps

  echo "[2/5] 基于反编译后的 Java 源码创建 CodeQL 数据库: ${DB_DIR}（模式: ${MODE}）"

  if [[ "${MODE}" == "build" ]]; then
    # 编译构建模式：通过 CODEQL_BUILD_CMD 指定构建命令
    BUILD_CMD="${CODEQL_BUILD_CMD:-}"
    if [[ -z "${BUILD_CMD}" ]]; then
      echo "错误: build 模式需要设置环境变量 CODEQL_BUILD_CMD 作为构建命令" >&2
      exit 1
    fi
    codeql database create "${DB_DIR}" \
      --language=java \
      --source-root="${DECOMP_DIR}" \
      --command="${BUILD_CMD}" \
      --overwrite \
      --working-dir="${WORK_DIR}"
  else
    # 源代码构建（默认，无需构建命令）
    codeql database create "${DB_DIR}" \
      --language=java \
      --source-root="${DECOMP_DIR}" \
      --build-mode=none \
      --overwrite \
      --working-dir="${WORK_DIR}"
  fi
fi

echo "[3/5] 运行当前包中的所有 .ql 查询，并生成 BQRS 与 CSV"

QUERY_ROOT="/opt/sast"
shopt -s nullglob
QL_FILES=("${QUERY_ROOT}"/*.ql)

if [[ ${#QL_FILES[@]} -eq 0 ]]; then
  echo "警告: 在 ${QUERY_ROOT} 下未找到任何 .ql 查询文件，跳过分析。" >&2
  exit 0
fi

for ql in "${QL_FILES[@]}"; do
  name="$(basename "${ql}" .ql)"
  bqrs="${OUTPUT_DIR}/${name}.bqrs"
  csv="${OUTPUT_DIR}/${name}.csv"

  echo "  - 运行查询: ${ql}"
  codeql query run "${ql}" --database="${DB_DIR}" --output="${bqrs}"

  echo "    解码 BQRS 为 CSV: ${csv}"
  codeql bqrs decode "${bqrs}" --format=csv --output="${csv}"
done

echo "[4/5] 完成查询与 CSV 转换。"
echo "[5/5] 所有结果已输出到目录: ${OUTPUT_DIR}"


