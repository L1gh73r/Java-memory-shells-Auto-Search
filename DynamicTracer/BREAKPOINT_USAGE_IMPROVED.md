# 断点功能改进版使用说明

## 🚀 核心改进

解决了原版本中JSON配置文件存储复杂Java代码容易出现格式错误的问题，提供了三种更好的代码配置方式。

## 💡 三种代码配置方式

### 1. 📋 代码模板系统（推荐）

使用预定义模板，通过参数化配置，避免复杂代码格式问题：

```json
{
  "id": "josearcher_search",
  "enabled": true,
  "targetClass": "org.springframework.context.event.SimpleApplicationEventMulticaster",
  "targetMethod": "multicastEvent",
  "breakpointType": "BEFORE",
  "codeTemplate": "JOSEARCHER_SEARCH",
  "codeParams": {
    "searchType": "Request",
    "blacklistType": "java.io.File",
    "maxDepth": "20",
    "reportPath": "/tmp"
  },
  "pauseExecution": true,
  "pauseDuration": 2000
}
```

**优点：**
- ✅ 无格式错误风险
- ✅ 参数化配置灵活
- ✅ 代码重用性高
- ✅ 易于维护

### 2. 📁 外部代码文件

将复杂代码保存到独立的`.java`文件中：

**配置：**
```json
{
  "id": "external_code",
  "enabled": true,
  "targetClass": ".*Controller.*",
  "targetMethod": ".*",
  "breakpointType": "BEFORE",
  "codeFile": "breakpoint-codes/josearcher-search.java",
  "pauseExecution": true,
  "pauseDuration": 1000
}
```

**外部文件：** `breakpoint-codes/josearcher-search.java`
```java
try {
    System.out.println("🔍 开始josearcher搜索...");
    
    List<me.gv7.tools.josearcher.entity.Keyword> keys = new ArrayList<>();
    keys.add(new me.gv7.tools.josearcher.entity.Keyword.Builder()
        .setField_type("Request").build());
    
    // ... 更多复杂代码 ...
    
    searcher.searchObject();
    System.out.println("✅ 搜索完成");
} catch (Exception e) {
    System.err.println("❌ 搜索失败: " + e.getMessage());
}
```

**优点：**
- ✅ 支持复杂代码逻辑
- ✅ 代码高亮和语法检查
- ✅ 版本控制友好
- ✅ 团队协作方便

### 3. 📝 简单直接代码

仅用于简单的单行或几行代码：

```json
{
  "id": "simple_log",
  "enabled": true,
  "targetClass": ".*Service.*",
  "targetMethod": "login",
  "breakpointType": "BEFORE",
  "customCode": "System.out.println(\"登录监控: \" + args[0]);",
  "pauseExecution": false
}
```

**适用场景：**
- ✅ 简单的日志输出
- ✅ 变量打印
- ✅ 基本的统计计数

## 📦 预定义代码模板

系统内置了多个常用代码模板：

### JOSEARCHER_SEARCH
用于执行josearcher对象搜索：
```json
"codeTemplate": "JOSEARCHER_SEARCH",
"codeParams": {
  "searchType": "Request",
  "blacklistType": "java.io.File",
  "maxDepth": "20",
  "reportPath": "/tmp"
}
```

### SIMPLE_LOG
简单的日志输出：
```json
"codeTemplate": "SIMPLE_LOG",
"codeParams": {
  "message": "方法调用监控"
}
```

### MEMORY_MONITOR
内存使用监控：
```json
"codeTemplate": "MEMORY_MONITOR",
"codeParams": {}
```

### PARAMETER_DUMP
参数转储：
```json
"codeTemplate": "PARAMETER_DUMP",
"codeParams": {}
```

### STACK_TRACE
堆栈跟踪：
```json
"codeTemplate": "STACK_TRACE",
"codeParams": {}
```

## 🔧 配置优先级

代码执行的优先级顺序：
1. **customCode** - 直接代码（最高优先级）
2. **codeTemplate** - 代码模板
3. **codeFile** - 外部文件（最低优先级）

## 📝 最佳实践

### 选择合适的配置方式：

| 代码复杂度 | 推荐方式 | 原因 |
|-----------|---------|------|
| 1-2行简单代码 | customCode | 配置简单，无额外文件 |
| 3-10行中等代码 | codeTemplate | 模板化，参数化配置 |
| 10+行复杂代码 | codeFile | 完整IDE支持，易维护 |

### 配置文件组织：
```
src/main/resources/rules/
├── breakpoint-config.json          # 主配置文件
├── breakpoint-config-simple.json   # 简化配置示例
└── breakpoint-codes/               # 外部代码目录
    ├── josearcher-search.java
    ├── security-check.java
    └── performance-analysis.java
```

## 🚀 使用示例

### 启动命令：
```bash
# 使用默认配置
-javaagent:DynamicTracer.jar

# 使用简化配置
-javaagent:DynamicTracer.jar -Ddynamictracer.breakpoint.config=breakpoint-config-simple.json

# 禁用断点功能
-javaagent:DynamicTracer.jar -Ddynamictracer.breakpoint.enabled=false
```

### 实际使用案例：

**案例1：Spring事件监控**
```json
{
  "id": "spring_event_monitor",
  "enabled": true,
  "targetClass": "org.springframework.context.event.SimpleApplicationEventMulticaster",
  "targetMethod": "multicastEvent",
  "breakpointType": "BEFORE",
  "codeTemplate": "JOSEARCHER_SEARCH",
  "codeParams": {
    "searchType": "Request",
    "maxDepth": "15",
    "reportPath": "/tmp/spring-events"
  }
}
```

**案例2：性能监控**
```json
{
  "id": "performance_monitor",
  "enabled": true,
  "targetClass": ".*Service.*",
  "targetMethod": ".*",
  "breakpointType": "BEFORE",
  "codeTemplate": "MEMORY_MONITOR"
}
```

## ⚠️ 注意事项

1. **JSON格式**：使用代码模板或外部文件避免JSON格式错误
2. **性能影响**：复杂代码会影响性能，建议在测试环境使用
3. **安全考虑**：自定义代码具有完整的系统权限，请谨慎使用
4. **依赖管理**：确保自定义代码所需的依赖库在classpath中

## 🔍 故障排除

### 常见问题：

**Q: 代码模板不生效？**
A: 检查模板名称是否正确，参数是否完整

**Q: 外部文件找不到？**
A: 确认文件路径相对于resources目录，检查文件是否存在

**Q: 复杂代码执行失败？**
A: 查看控制台错误信息，检查依赖库是否可用

**Q: 断点不触发？**
A: 检查类名和方法名的正则表达式是否匹配目标方法

这个改进版本彻底解决了JSON配置文件中存储复杂代码的问题，提供了更加灵活和维护友好的解决方案！
