#!/bin/bash

# Configuration
TARGET_JAR="/app/atools/benchmarks/druid_filter_mem-0.0.1-SNAPSHOT.jar"
AGENT_JAR="/app/atools/agent/DynamicTracer-1.0-SNAPSHOT.jar"
CONFIG_PATH="/app/atools/agent/config/breakpoint-config.json"
OUTPUT_DIR="/app/atools/agent/jos_result"
LOG_FILE="/app/app.log"
TRIGGER_URL="http://localhost:8080/a.css"
FILTER_SCRIPT="/app/scripts/filter_logs.py"

echo "Starting Pre-Analysis..."

# Ensure output directory exists
mkdir -p "$OUTPUT_DIR"

# Start the application with the agent，优先使用容器内的 JDK8
echo "Starting Java application (JDK8 if available)..."
JAVA8_BIN="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}/bin/java"
"$JAVA8_BIN" \
     -javaagent:"$AGENT_JAR" \
     -Dctfagent.db.dir="$OUTPUT_DIR" \
     -Dctfagent.csv.path="/app/codeql-output/test2.csv" \
     -jar "$TARGET_JAR" > "$LOG_FILE" 2>&1 &

    #  -Ddynamictracer.breakpoint.config="$CONFIG_PATH" \
    #  -Ddynamictracer.breakpoint.enabled=true \

APP_PID=$!
echo "Application PID: $APP_PID"

# Wait for application to start
echo "Waiting for application to start..."
MAX_RETRIES=60
COUNT=0
STARTED=false

while [ $COUNT -lt $MAX_RETRIES ]; do
    if curl -s "http://localhost:8080/actuator/health" > /dev/null; then
        STARTED=true
        break
    fi
    # Fallback check: grep log for "Started"
    if grep -q "Started .* in .* seconds" "$LOG_FILE"; then
        STARTED=true
        break
    fi
    sleep 2
    COUNT=$((COUNT+1))
    echo "Waiting... ($COUNT/$MAX_RETRIES)"
done

if [ "$STARTED" = false ]; then
    echo "Error: Application failed to start."
    cat "$LOG_FILE"
    kill $APP_PID
    exit 1
fi

echo "Application started successfully."

# Trigger request
echo "Triggering request to $TRIGGER_URL..."
curl -v "$TRIGGER_URL"

# Wait a bit for logs to be flushed
sleep 5

# Kill the application
echo "Stopping application..."
kill $APP_PID
wait $APP_PID 2>/dev/null

# Process logs
echo "Processing logs..."
# We need to find the generated JSON log file. 
# The agent generates a file like <ID>-<ClassName>_result.jsonl in OUTPUT_DIR
# We will assume the latest modified file is the one we want, or process all of them.

# For this specific task, we want to filter the "ctf_hook_logs" if that's what the agent produces,
# OR if the agent produces the jsonl result directly, we might just need to ensure it's there.
# Based on previous context, the user had a "ctf_hook_logs_....json" file.
# Let's look for that pattern.

LOG_JSON=$(find "$OUTPUT_DIR" -name "ctf_hook_logs_*.json" | sort -r | head -n 1)

if [ -z "$LOG_JSON" ]; then
    echo "Warning: No ctf_hook_logs json file found in $OUTPUT_DIR"
    echo "=== Application Log Dump ==="
    cat "$LOG_FILE"
    echo "============================"
else
    echo "Found log file: $LOG_JSON"
    # Run the python filter script
    # We need to copy the filter script to the container or mount it.
    # For now, I'll inline a simple python script or assume it's mounted.
    # Let's assume we mount the scripts dir to /app/scripts
    
    if [ -f "$FILTER_SCRIPT" ]; then
        OUTPUT_JSON="$OUTPUT_DIR/analysis_result.json"
        python3 "$FILTER_SCRIPT" "$LOG_JSON" "$OUTPUT_JSON"
        echo "Log processing complete. Result saved to $OUTPUT_JSON"
    else
        echo "Error: Filter script not found at $FILTER_SCRIPT"
    fi
fi

echo "=== 完整应用日志 ==="
cat "$LOG_FILE"
echo "===================="

echo "Pre-Analysis Complete."
