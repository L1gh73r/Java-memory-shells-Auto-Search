# N8N Webhook集成功能

## 概述

Showmaker项目现在支持在workflow容器启动完成后，自动触发n8n webhook，实现与n8n工作流的集成。这个功能允许您使用n8n的可视化工作流来处理Showmaker的分析结果。

## 功能特点

- 自动触发：workflow容器启动后自动调用n8n webhook
- 配置灵活：通过配置文件自定义n8n服务器地址和webhook路径
- 数据传递：自动传递容器信息和基本元数据到n8n工作流
- 回调支持：预留回调接口，用于接收n8n工作流的结果

## 配置说明

### 1. 修改showmaker-config.yml

在项目根目录的`showmaker-config.yml`文件中添加n8n配置：

```yaml
# n8n 工作流配置
n8n:
  # n8n 服务器配置
  server:
    # n8n 服务器地址（可以是 IP 或域名）
    host: "192.168.215.2"
    # n8n 服务器端口
    port: 5678
    # n8n webhook 路径（不含基础URL）
    webhook_path: "/webhook/fb80a628-9f5e-48e3-9863-775af53004a8"
    # 完整的 webhook URL（如果不设置，将根据 host、port 和 webhook_path 自动生成）
    webhook_url: ""
  
  # 工作流配置
  workflow:
    # 工作流名称（用于日志和标识）
    name: "showmaker-analysis-workflow"
    # 工作流超时时间（秒）
    timeout: 3600
    # 重试次数
    retry_count: 3
    # 重试间隔（秒）
    retry_interval: 30
```

### 2. 环境变量配置

您也可以通过环境变量覆盖配置：

```bash
export N8N_HOST=192.168.215.2
export N8N_PORT=5678
export N8N_WEBHOOK_PATH=/webhook/fb80a628-9f5e-48e3-9863-775af53004a8
export N8N_WORKFLOW_NAME=showmaker-analysis-workflow
export N8N_WORKFLOW_TIMEOUT=3600
```

## 使用方法

### 1. 启动Showmaker流程

按照正常流程启动Showmaker：

```bash
./run_pipeline.sh
```

当workflow容器启动完成后，它会自动触发n8n webhook。

### 2. 测试webhook功能

您可以使用提供的测试脚本来模拟n8n服务器，验证webhook功能：

```bash
# 启动模拟n8n服务器
python3 scripts/test/test_webhook.py

# 在另一个终端启动workflow容器
docker-compose up -d workflow
```

### 3. 查看日志

查看workflow容器的日志，确认webhook是否成功触发：

```bash
docker-compose logs workflow
```

## Webhook数据格式

### 发送到n8n的数据

workflow容器发送到n8n webhook的数据格式如下：

```json
{
  "workflow_name": "showmaker-analysis-workflow",
  "trigger_data": {
    "source": "workflow-integration-hub",
    "timestamp": 1701234567890,
    "container_info": {
      "container_id": "abc123def456",
      "container_ip": "172.20.0.5",
      "java_version": "17.0.2"
    }
  },
  "callback_url": "http://workflow:7781/api/n8n/result",
  "timeout": 3600
}
```

### n8n回调数据格式

n8n工作流完成后，可以发送回调数据到workflow容器：

```json
{
  "status": "success",
  "message": "工作流执行完成",
  "results": {
    "analysis_results": [...],
    "poc_candidates": [...]
  }
}
```

## 故障排除

### 1. Webhook触发失败

检查以下几点：

- 确认n8n服务器地址和端口配置正确
- 确认webhook路径配置正确
- 检查网络连接，确保workflow容器可以访问n8n服务器
- 查看workflow容器日志，获取详细错误信息

### 2. 配置不生效

确认以下几点：

- 检查`showmaker-config.yml`文件格式是否正确
- 确认环境变量设置是否正确
- 重新构建workflow容器：`docker-compose build workflow`

### 3. 回调接收失败

检查以下几点：

- 确认n8n工作流中的回调URL配置正确
- 确认n8n服务器可以访问workflow容器（网络配置）
- 查看n8n工作流执行日志

## 下一步计划

1. 完善n8n工作流模板
2. 实现基于n8n结果的验证容器自动触发
3. 添加更多数据传递和处理功能
4. 提供n8n工作流导入工具

## 相关文件

- `showmaker-config.yml` - 全局配置文件
- `workflow-integration-hub/src/main/java/com/workflow/service/WebhookService.java` - Webhook服务实现
- `workflow-integration-hub/src/main/java/com/workflow/controller/N8nCallbackController.java` - 回调控制器
- `scripts/test/test_webhook.py` - Webhook测试脚本
