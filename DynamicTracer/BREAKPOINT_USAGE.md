# 断点功能使用说明

## 功能概述

DynamicTracer 的断点功能允许你在指定的方法执行时设置断点，并执行自定义代码。这个功能对于调试、监控和分析应用程序非常有用。

## ⚡ 重要改进：解决代码配置问题

针对JSON配置文件中直接存储复杂Java代码容易出现格式错误的问题，现在提供了**三种更好的代码配置方式**：

### 1. 📋 代码模板系统 (推荐)
使用预定义的代码模板，通过参数化配置，避免复杂代码格式问题：

```json
{
  "codeTemplate": "JOSEARCHER_SEARCH",
  "codeParams": {
    "searchType": "Request", 
    "maxDepth": "20",
    "reportPath": "/tmp"
  }
}
```

### 2. 📁 外部代码文件
将复杂代码保存到独立的`.java`文件中，JSON只需引用文件路径：

```json
{
  "codeFile": "breakpoint-codes/josearcher-search.java"
}
```

### 3. 📝 简单直接代码
只有简单代码时才直接在JSON中配置：

```json
{
  "customCode": "System.out.println(\"简单输出\");"
}
```

## 主要特性

- ✅ **灵活的断点配置** - 通过 JSON 配置文件定义断点
- ✅ **方法执行前后断点** - 支持 BEFORE 和 AFTER 两种断点类型
- ✅ **条件断点** - 支持总是触发或参数匹配条件
- ✅ **自定义代码执行** - 在断点触发时执行自定义 Java 代码
- ✅ **程序暂停控制** - 可配置暂停程序执行的时长
- ✅ **独立日志表** - 断点日志记录到专门的数据库表
- ✅ **防重入机制** - 避免断点代码递归调用
- ✅ **黑名单保护** - 自动跳过系统关键类

## 配置文件

断点配置文件位于：`src/main/resources/rules/breakpoint-config.json`

### 配置示例

```json
{
  "breakpoints": [
    {
      "id": "login_monitor",
      "enabled": true,
      "targetClass": ".*Service.*",
      "targetMethod": "login",
      "breakpointType": "BEFORE",
      "conditionType": "ALWAYS",
      "customCode": "System.out.println(\"🔐 用户登录监控\"); Thread.sleep(1000);",
      "pauseExecution": true,
      "pauseDuration": 1000,
      "logToDatabase": true
    }
  ]
}
```

### 配置参数说明

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | String | 断点唯一标识符 |
| `enabled` | Boolean | 是否启用此断点 |
| `targetClass` | String | 目标类名（支持正则表达式） |
| `targetMethod` | String | 目标方法名（支持正则表达式） |
| `breakpointType` | String | 断点类型：`BEFORE`（方法执行前）或 `AFTER`（方法执行后） |
| `conditionType` | String | 触发条件：`ALWAYS`（总是触发）或 `PARAMETER_MATCH`（参数匹配） |
| `customCode` | String | 断点触发时执行的自定义 Java 代码 |
| `pauseExecution` | Boolean | 是否暂停程序执行 |
| `pauseDuration` | Long | 暂停时长（毫秒） |
| `logToDatabase` | Boolean | 是否记录到数据库 |

## 使用方法

### 1. 启动参数

```bash
# 基本启动（断点功能默认启用）
-javaagent:DynamicTracer.jar

# 指定断点配置文件
-javaagent:DynamicTracer.jar -Ddynamictracer.breakpoint.config=my-breakpoint-config.json

# 禁用断点功能
-javaagent:DynamicTracer.jar -Ddynamictracer.breakpoint.enabled=false
```

### 2. 配置断点

编辑 `breakpoint-config.json` 文件，添加你需要的断点配置：

```json
{
  "breakpoints": [
    {
      "id": "user_service_monitor",
      "enabled": true,
      "targetClass": ".*UserService.*",
      "targetMethod": ".*",
      "breakpointType": "BEFORE",
      "conditionType": "ALWAYS",
      "customCode": "System.out.println(\"UserService 方法调用: \" + Thread.currentThread().getName());",
      "pauseExecution": false,
      "pauseDuration": 0,
      "logToDatabase": true
    }
  ]
}
```

### 3. 自定义代码示例

断点的 `customCode` 字段支持任何有效的 Java 代码：

```java
// 输出参数信息
"System.out.println(\"参数: \" + java.util.Arrays.toString(args));"

// 输出内存使用情况
"Runtime rt = Runtime.getRuntime(); System.out.println(\"内存: \" + (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024 + \"MB\");"

// 暂停执行
"Thread.sleep(2000);"

// 输出当前时间
"System.out.println(\"时间: \" + new java.text.SimpleDateFormat(\"HH:mm:ss\").format(new java.util.Date()));"

// 组合多个操作
"System.out.println(\"断点触发\"); Thread.sleep(1000); System.out.println(\"继续执行\");"
```

## 数据库日志

断点日志会记录到 `breakpoint_logs` 表中，包含以下字段：

- `id` - 自增主键
- `timestamp` - 时间戳
- `date_time` - 格式化时间
- `breakpoint_id` - 断点ID
- `class_name` - 类名
- `method_name` - 方法名
- `breakpoint_type` - 断点类型
- `parameters` - 方法参数
- `stack_trace` - 堆栈跟踪
- `status` - 状态（TRIGGERED, EXECUTED, ERROR）
- `thread_name` - 线程名
- `thread_id` - 线程ID
- `execution_time_ms` - 执行耗时
- `created_at` - 创建时间

## 预设断点示例

配置文件中包含了几个预设的断点示例：

1. **登录监控** - 监控所有包含 "Service" 的类中的 `login` 方法
2. **保存监控** - 监控所有以 `save` 开头的方法
3. **UserService监控** - 监控所有 UserService 类的方法调用
4. **安全检查** - 监控所有包含 "Security" 的类的方法
5. **数据库操作** - 监控 Repository 和 Dao 类的数据库操作方法（默认禁用）

## 注意事项

1. **性能影响** - 断点会对性能产生影响，特别是在高频调用的方法上
2. **线程安全** - 断点代码应该是线程安全的
3. **异常处理** - 断点代码中的异常不会影响原方法执行
4. **正则表达式** - 类名和方法名支持 Java 正则表达式
5. **黑名单保护** - 系统关键类（如 java.*、sun.* 等）会被自动跳过

## 故障排除

### 断点不触发
- 检查 `enabled` 字段是否为 `true`
- 检查类名和方法名的正则表达式是否正确
- 查看控制台输出是否有错误信息

### 性能问题
- 减少断点数量
- 避免在高频方法上设置断点
- 简化自定义代码逻辑
- 设置 `pauseExecution` 为 `false`

### 日志查看
断点日志保存在 H2 数据库中，可以使用 SQL 查询：

```sql
-- 查看所有断点日志
SELECT * FROM breakpoint_logs ORDER BY timestamp DESC;

-- 查看特定断点的日志
SELECT * FROM breakpoint_logs WHERE breakpoint_id = 'login_monitor';

-- 查看最近的断点触发
SELECT * FROM breakpoint_logs WHERE timestamp > (CURRENT_TIMESTAMP - INTERVAL '1' HOUR);
```

## 扩展功能

未来可能添加的功能：
- 远程控制接口
- 更复杂的条件表达式
- 脚本语言支持（JavaScript、Groovy）
- 断点统计和分析
- 实时断点配置更新
