#!/bin/bash

set -e

# ============================
# 批量内存搜索（JOSEarcher）容器脚本
# ============================
# 作用（尽量对齐 workflow 中 MemorySearchService 的行为）：
#   - 使用 JDK8 + DynamicTracer Agent
#   - 读取预分析容器输出的 analysis_result.json（基于 hook 日志的事件流）
#   - 从 JSON 中提取 CLASS_NAME + METHOD_NAME 作为目标，逐条构造断点配置做 JOSEarcher 内存搜索
#   - 每条搜索：
#       * 启动目标应用
#       * 等待“应用启动完成”（日志 / 固定延时）
#       * 多次重试触发 HTTP 请求
#       * 轮询等待对应的 *_result.jsonl 结果文件出现
#   - 结果文件命名规则对齐 MemorySearchService.generateResultFileName：
#       searchId + "-" + ShortClassName + "_result.jsonl"
#
# 注意：
#   - 本脚本的参数全部通过挂载文件和固定路径约定，不需要外部传参
#   - 与 pre-analysis 容器（只生成 hook logs）职责不同，这里是“按 CSV 驱动的断点 + JOSEarcher 搜索”

# 目标应用与 Agent 配置（通过 docker-compose 卷挂载）
# TARGET_JAR 可通过环境变量注入，默认为示例 JAR
TARGET_JAR="${TARGET_JAR:-/app/atools/benchmarks/druid_filter_mem-0.0.1-SNAPSHOT.jar}"
AGENT_JAR="/app/atools/agent/DynamicTracer-1.0-SNAPSHOT.jar"

# 预分析容器输出的 JSON 文件（原始输入，不做修改）
ANALYSIS_JSON="/app/atools/agent/jos_result/analysis_result.json"

# DynamicTracer 断点配置（runtime 文件，每条 CSV 记录会覆盖写一遍）
BREAKPOINT_CONFIG_RUNTIME="/app/atools/agent/config/breakpoint-config.runtime.json"

# JOSEarcher 输出目录（需与宿主机挂载一致）
OUTPUT_DIR="/app/atools/agent/jos_result"

# 日志目录（记录每个 CSV 条目对应的一次扫描日志）
LOG_DIR="/app/logs"
mkdir -p "$OUTPUT_DIR" "$LOG_DIR"

# 映射文件：记录每次内存搜索对应的目标类/方法/OWNER_CLASS 与结果文件名
MAPPING_FILE="$OUTPUT_DIR/memory_search_mapping.jsonl"

# 用于“监听”输出目录中新产生的文件（包括 *_result.jsonl 和 *_error.log 等）
declare -A SEEN_FILES
for f in "$OUTPUT_DIR"/*; do
  if [ -f "$f" ]; then
    SEEN_FILES["$f"]=1
  fi
done

# 触发 HTTP 请求的 URL（用于激活内存行为，复用 workflow 里默认的 a.css）
TRIGGER_URL="${TRIGGER_URL:-http://localhost:8080/a.css}"
TRIGGER_METHOD="${TRIGGER_METHOD:-GET}"
MAX_TRIGGER_RETRIES="${MAX_TRIGGER_RETRIES:-3}"
TRIGGER_DELAY_SECONDS="${TRIGGER_DELAY_SECONDS:-3}"

# 启动检测相关（简单版：优先看日志里的 Started ... in ... seconds，其次固定延时）
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-60}"
FIXED_STARTUP_DELAY_SECONDS="${FIXED_STARTUP_DELAY_SECONDS:-10}"

echo "===== 启动批量内存搜索（JOSEarcher）====="
echo "目标 Jar: $TARGET_JAR"
echo "Agent Jar: $AGENT_JAR"
echo "原始分析输入 JSON: $ANALYSIS_JSON"
echo "输出目录: $OUTPUT_DIR"

if [ ! -f "$TARGET_JAR" ]; then
  echo "错误：未找到目标 Jar: $TARGET_JAR"
  exit 1
fi

if [ ! -f "$AGENT_JAR" ]; then
  echo "错误：未找到 Agent Jar: $AGENT_JAR"
  exit 1
fi

if [ ! -f "$ANALYSIS_JSON" ]; then
  echo "错误：未找到分析结果 JSON: $ANALYSIS_JSON"
  echo "请先确保预分析容器已成功生成 analysis_result.json。"
  exit 1
fi

# 基于 CLASS_NAME, METHOD_NAME, OWNER_CLASS 去重，生成新的 JSON 作为本 agent 的输入
DEDUP_JSON="$OUTPUT_DIR/analysis_result.dedup.json"
echo "根据 CLASS_NAME, METHOD_NAME, OWNER_CLASS 去重生成: $DEDUP_JSON"

python3 - "$ANALYSIS_JSON" "$DEDUP_JSON" << 'PY'
import sys, json

src, dst = sys.argv[1], sys.argv[2]

try:
    with open(src, 'r', encoding='utf-8') as f:
        data = json.load(f)
except Exception as e:
    print(f"[PY] 读取原始 JSON 失败: {e}", file=sys.stderr)
    sys.exit(1)

seen = set()
dedup = []
for item in data:
    cls = (item.get("CLASS_NAME") or "").strip()
    method = (item.get("METHOD_NAME") or "").strip()
    owner = (item.get("OWNER_CLASS") or "").strip()
    if not cls or not method or not owner:
        continue
    key = (cls, method, owner)
    if key in seen:
        continue
    seen.add(key)
    dedup.append(item)

try:
    with open(dst, 'w', encoding='utf-8') as f:
        json.dump(dedup, f, ensure_ascii=False, indent=2)
except Exception as e:
    print(f"[PY] 写入去重后 JSON 失败: {e}", file=sys.stderr)
    sys.exit(1)

print(f"[PY] 去重完成，原始条目数={len(data)}, 去重后={len(dedup)}")
PY

# 后续逻辑统一使用去重后的 JSON，保证不影响原始文件
ANALYSIS_JSON="$DEDUP_JSON"
echo "实际用于内存搜索的输入 JSON: $ANALYSIS_JSON"

# 每轮搜索前，先清理旧的 *_result.jsonl，模拟 MemorySearchService.cleanPreviousResults()
echo "清理旧的 JOSE 结果文件 (*.jsonl)..."
find "$OUTPUT_DIR" -maxdepth 1 -name "*.jsonl" -type f -delete || true

# 根据 MemorySearchService.generateResultFileName 的规则构造结果文件名
generate_result_filename() {
  local search_id="$1"
  local target_class="$2"
  local short_class="$target_class"
  if [[ "$short_class" == *.* ]]; then
    short_class="${short_class##*.}"
  fi
  echo "${search_id}-${short_class}_result.jsonl"
}

wait_for_startup() {
  local log_file="$1"
  local timeout="$2"
  local start_ts
  start_ts=$(date +%s)

  echo "  [启动检测] 最长等待 ${timeout}s..."
  while true; do
    # 1) 日志模式：Started ... in ... seconds
    if grep -q "Started .* in .* seconds" "$log_file" 2>/dev/null; then
      echo "  [启动检测] 检测到启动日志模式，认为应用已启动。"
      return 0
    fi

    # 2) HTTP 健康检查（/actuator/health），如果 200 则认为 OK
    if curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/actuator/health" 2>/dev/null | grep -q "^2"; then
      echo "  [启动检测] /actuator/health 返回 2xx，认为应用已启动。"
      return 0
    fi

    local now
    now=$(date +%s)
    if (( now - start_ts >= timeout )); then
      echo "  [启动检测] 超时 ${timeout}s，退回固定延迟策略。"
      break
    fi

    sleep 1
  done

  # 3) 兜底：固定延迟
  echo "  [启动检测] 使用固定延迟 ${FIXED_STARTUP_DELAY_SECONDS}s 兜底..."
  sleep "$FIXED_STARTUP_DELAY_SECONDS"
  return 0
}

trigger_requests() {
  local url="$1"
  local method="$2"
  echo "  [触发请求] URL=$url, METHOD=$method, 重试次数=$MAX_TRIGGER_RETRIES"

  for ((i=1; i<=MAX_TRIGGER_RETRIES; i++)); do
    echo "  [触发请求] 第 ${i}/${MAX_TRIGGER_RETRIES} 次..."
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$url" || echo "000")
    echo "  [触发请求] HTTP 状态码: $code"

    # 和 MemorySearchService.sendHttpRequest 一致：2xx/3xx 认为成功
    if [[ "$code" =~ ^2|3 ]]; then
      echo "  [触发请求] 成功，等待 ${TRIGGER_DELAY_SECONDS}s 让 JOSE 写结果..."
      sleep "$TRIGGER_DELAY_SECONDS"
      return 0
    fi

    sleep 1
  done

  echo "  [触发请求] 多次重试仍未成功，继续等待结果文件（可能有被动触发）。"
  return 1
}

wait_for_result_file() {
  local result_file="$1"
  local timeout="$2"
  local start_ts
  start_ts=$(date +%s)

  echo "  [结果检测] 等待结果文件: $result_file (最多 ${timeout}s)"
  while true; do
    if [ -f "$result_file" ]; then
      echo "  [结果检测] 检测到结果文件。"
      return 0
    fi

    local now
    now=$(date +%s)
    if (( now - start_ts >= timeout )); then
      echo "  [结果检测] 超时 ${timeout}s，未检测到结果文件。"
      return 1
    fi

    sleep 1
  done
}

report_new_files() {
  # 遍历当前目录下所有文件，找出之前没见过的
  for f in "$OUTPUT_DIR"/*; do
    if [ -f "$f" ] && [ -z "${SEEN_FILES["$f"]}" ]; then
      SEEN_FILES["$f"]=1
      echo "  [文件监控] 检测到新文件: $f"
    fi
  done
}

# 从去重后的 analysis_result.dedup.json 中提取 (CLASS_NAME, METHOD_NAME, OWNER_CLASS) 作为搜索目标
# 使用 Python 帮忙解析 JSON，输出格式：行号,CLASS_NAME,METHOD_NAME,OWNER_CLASS
# 注意：三字段去重已经在生成 analysis_result.dedup.json 时完成，这里不再做二次去重
generate_targets_from_json() {
  python3 - "$ANALYSIS_JSON" << 'PY'
import sys, json

path = sys.argv[1]
try:
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
except Exception as e:
    print(f"[PY] 读取 JSON 失败: {e}", file=sys.stderr)
    sys.exit(1)

idx = 0
for item in data:
    cls = (item.get("CLASS_NAME") or "").strip()
    method = (item.get("METHOD_NAME") or "").strip()
    owner = (item.get("OWNER_CLASS") or "").strip()
    if not cls or not method or not owner:
        continue
    idx += 1
    # 输出格式：序号,类名,方法名,OWNER_CLASS
    print(f"{idx},{cls},{method},{owner}")
PY
}

INDEX=0
while IFS=, read -r ROW_INDEX TARGET_CLASS TARGET_METHOD TARGET_OWNER; do
  INDEX=$((INDEX + 1))

  if [ -z "$TARGET_CLASS" ] || [ -z "$TARGET_METHOD" ] || [ -z "$TARGET_OWNER" ]; then
    echo "[$INDEX] 跳过：CLASS_NAME 或 METHOD_NAME 或 OWNER_CLASS 为空，ROW_INDEX='$ROW_INDEX'"
    continue
  fi

  # searchId 规则：bp_search_<index>，便于在结果文件名中区分
  SEARCH_ID="bp_search_${INDEX}"
  SHORT_CLASS="${TARGET_CLASS##*.}"
  RESULT_FILE_NAME="$(generate_result_filename "$SEARCH_ID" "$TARGET_CLASS")"
  RESULT_FILE_PATH="$OUTPUT_DIR/$RESULT_FILE_NAME"

  echo "[$INDEX] 开始内存搜索:"
  echo "      targetClass = $TARGET_CLASS"
  echo "      targetMethod = $TARGET_METHOD"
  echo "      searchId = $SEARCH_ID"
  echo "      resultFile = $RESULT_FILE_PATH"

  # 每条搜索开始前，删除同名旧结果，避免误读
  rm -f "$RESULT_FILE_PATH" 2>/dev/null || true

  # 构造断点配置 runtime JSON（与 MemorySearchService.updateBreakpointConfig 结构一致）
  cat > "$BREAKPOINT_CONFIG_RUNTIME" <<EOF
{
  "id": "$SEARCH_ID",
  "targetClass": "$TARGET_CLASS",
  "targetMethod": "$TARGET_METHOD"
}
EOF

  # 为本次扫描准备独立日志文件
  ENTRY_LOG="$LOG_DIR/memory_search_${INDEX}_${SHORT_CLASS}_${TARGET_METHOD}.log"

  # 启动目标应用（JDK8 + Agent）
  echo "[$INDEX] 启动目标应用..."
  java \
    -javaagent:"$AGENT_JAR" \
    -Ddynamictracer.breakpoint.config="$BREAKPOINT_CONFIG_RUNTIME" \
    -Ddynamictracer.breakpoint.enabled=true \
    -Ddynamictracer.josearcher.output.path="$OUTPUT_DIR" \
    -Dctfagent.csv.path=/dev/null \
    -jar "$TARGET_JAR" > "$ENTRY_LOG" 2>&1 &

  APP_PID=$!

  echo "[$INDEX] 等待应用启动..."
  wait_for_startup "$ENTRY_LOG" "$STARTUP_TIMEOUT_SECONDS"

  echo "[$INDEX] 发送触发请求..."
  trigger_requests "$TRIGGER_URL" "$TRIGGER_METHOD" || true

  echo "[$INDEX] 等待 JOSE 结果文件..."
  wait_for_result_file "$RESULT_FILE_PATH" 20 || true

  # 结束应用（优先按 PID，直接使用 kill -9，必要时再兜底清理）
  echo "[$INDEX] 停止目标应用 (PID=$APP_PID)..."
  # 1) 先尝试普通 kill，给应用一点优雅退出的机会
  kill "$APP_PID" 2>/dev/null || true
  sleep 1

  # 2) 如果该 PID 仍存在，使用 kill -9 强制终止
  if ps -p "$APP_PID" > /dev/null 2>&1; then
    echo "[$INDEX] 目标 PID 仍存活，使用 kill -9 $APP_PID 强制终止..."
    kill -9 "$APP_PID" 2>/dev/null || true
  fi

  # 3) 兜底：如仍有残留 JVM，可按 Jar 名和 Agent 名强杀（容错处理）
  pkill -f "$TARGET_JAR" 2>/dev/null || true
  pkill -f "DynamicTracer-1.0-SNAPSHOT.jar" 2>/dev/null || true

  # 4) 最后再 wait 一次，避免僵尸进程
  wait "$APP_PID" 2>/dev/null || true

  # 5) 记录一次搜索映射关系，便于 workflow 按 (CLASS_NAME, METHOD_NAME, OWNER_CLASS) 精确关联
  if [ -n "$MAPPING_FILE" ]; then
    echo "  [映射记录] 追加到 $MAPPING_FILE"
    cat >> "$MAPPING_FILE" <<EOF
{"searchId":"$SEARCH_ID","targetClass":"$TARGET_CLASS","targetMethod":"$TARGET_METHOD","ownerClass":"$TARGET_OWNER","resultFile":"$(basename "$RESULT_FILE_PATH")"}
EOF
  fi

  # 一轮结束后，检查输出目录是否出现了新文件（包括 *_result.jsonl 或 *_error.log）
  report_new_files

  if [ -f "$RESULT_FILE_PATH" ]; then
    echo "[$INDEX] 单条内存搜索完成，生成结果文件: $RESULT_FILE_PATH"
  else
    echo "[$INDEX] 单条内存搜索结束，但未检测到结果文件（可能本次无命中）。"
  fi
done < <(generate_targets_from_json)

echo "===== 批量内存搜索全部完成 ====="


