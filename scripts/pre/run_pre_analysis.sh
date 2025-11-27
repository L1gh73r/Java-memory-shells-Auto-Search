#!/bin/bash

# 基本配置（预分析容器专用）
# - TARGET_JAR: 需要挂载进来的目标应用 JAR（被 DynamicTracer 分析的应用）
# - AGENT_JAR : DynamicTracer Agent JAR（通过卷挂载到 /app/atools/agent 下）
# - OUTPUT_DIR: JOSEarcher / DynamicTracer 输出结果目录（ctf_hook_logs_*.json 等）
# - LOG_FILE  : 目标应用标准输出日志，便于排查启动&hook 问题
# - TRIGGER_URL: 用于触发内存行为的 HTTP 请求地址（例如 /a.css）
#   TARGET_JAR 可通过环境变量注入，默认为示例 JAR
TARGET_JAR="${TARGET_JAR:-/app/atools/benchmarks/druid_filter_mem-0.0.1-SNAPSHOT.jar}"
AGENT_JAR="/app/atools/agent/DynamicTracer-1.0-SNAPSHOT.jar"
OUTPUT_DIR="/app/atools/agent/jos_result"
LOG_FILE="/app/app.log"
TRIGGER_URL="http://localhost:8080/a.css"
FILTER_SCRIPT="/app/scripts/filter_logs.py"

echo "===== 启动内存预分析（DynamicTracer + JOSEarcher）====="

# 确保结果输出目录存在
mkdir -p "$OUTPUT_DIR"

# 使用容器内的 JDK8 启动目标应用，并挂载 DynamicTracer Agent。
# 这里通过 CodeQL 生成的 CSV（/app/codeql-output/test2.csv）驱动 DynamicTracer 的 hook 规则，
# 与 workflow 内部的基于断点配置的内存搜索是两条独立链路。
echo "启动目标 Java 应用（JDK8 + DynamicTracer Agent）..."
java \
  -javaagent:"$AGENT_JAR" \
  -Dctfagent.db.dir="$OUTPUT_DIR" \
  -Dctfagent.csv.path="/app/codeql-output/test2.csv" \
  -jar "$TARGET_JAR" > "$LOG_FILE" 2>&1 &

APP_PID=$!
echo "Application PID: $APP_PID"

# 等待应用启动（通过 /actuator/health 或启动日志判断）
echo "等待目标应用启动中..."
MAX_RETRIES=60
COUNT=0
STARTED=false

while [ $COUNT -lt $MAX_RETRIES ]; do
    if curl -s "http://localhost:8080/actuator/health" > /dev/null; then
        STARTED=true
        break
    fi
    # 兜底：根据日志中的 “Started ... in ... seconds” 关键字判断是否启动完成
    if grep -q "Started .* in .* seconds" "$LOG_FILE"; then
        STARTED=true
        break
    fi
    sleep 2
    COUNT=$((COUNT+1))
    echo "等待中... ($COUNT/$MAX_RETRIES)"
done

if [ "$STARTED" = false ]; then
    echo "错误：目标应用启动失败，输出如下："
    cat "$LOG_FILE"
    kill $APP_PID
    exit 1
fi

echo "目标应用启动成功。"

# 触发一次 HTTP 请求，激活动态行为（例如触发事件广播 / 潜在内存马挂载点）
echo "向目标应用发送触发请求: $TRIGGER_URL ..."
curl -v "$TRIGGER_URL"

# 等待一小段时间，确保日志和内存搜索结果已经刷新到磁盘
sleep 5

# 停止目标应用
echo "停止目标应用..."
kill $APP_PID
wait $APP_PID 2>/dev/null

# 处理内存预分析日志
echo "开始处理内存预分析日志..."
# 当前预分析主要依赖 DynamicTracer 写出的 ctf_hook_logs_*.json，
# 这里选取最新的一个作为输入，并通过 Python 过滤脚本生成 analysis_result.json，
# 供 workflow 的 /api/read-json-data 等接口直接消费。
LOG_JSON=$(find "$OUTPUT_DIR" -name "ctf_hook_logs_*.json" | sort -r | head -n 1)

if [ -z "$LOG_JSON" ]; then
    echo "警告：在 $OUTPUT_DIR 中未找到任何 ctf_hook_logs_*.json"
    echo "=== 目标应用原始日志如下 ==="
    cat "$LOG_FILE"
    echo "==========================="
else
    echo "发现预分析日志文件: $LOG_JSON"
    # 调用 Python 过滤脚本，将原始 hook 日志转为更结构化的 analysis_result.json
    if [ -f "$FILTER_SCRIPT" ]; then
        OUTPUT_JSON="$OUTPUT_DIR/analysis_result.json"
        python3 "$FILTER_SCRIPT" "$LOG_JSON" "$OUTPUT_JSON"
        echo "预分析日志处理完成，结果已保存到: $OUTPUT_JSON"
    else
        echo "错误：未找到过滤脚本: $FILTER_SCRIPT"
    fi
fi

echo "=== 目标应用完整日志 ==="
cat "$LOG_FILE"
echo "======================"

echo "===== 内存预分析完成 ====="


