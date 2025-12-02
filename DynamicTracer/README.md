# DynamicTracer

## 介绍
DynamicTracer 是一个基于 Java Agent 技术的动态插桩和调试工具，主要功能包括：

- **动态插桩**：根据配置文件动态Hook指定的方法，收集运行时信息
- **对象引用追踪**：分析对象的引用关系，帮助理解内存结构
- **高性能日志记录**：使用SQLite数据库高效记录Hook到的信息
- **断点调试**：（规划中）支持在运行时设置断点并执行任意代码

## 核心功能

### 1. 动态方法Hook
- 支持根据CSV配置文件动态Hook指定的方法
- 记录方法调用参数、堆栈信息、对象引用关系
- 纯监控模式，不影响原程序执行逻辑

### 2. 对象引用追踪
- 静态分析信息利用：基于CSV数据建立引用关系映射
- 堆栈信息分析：从调用堆栈中寻找对象引用
- 帮助分析内存泄漏和对象生命周期

### 3. 高性能数据收集
- 使用SQLite数据库存储Hook信息
- 批量写入优化，减少I/O开销
- 支持异步日志记录

## 简单使用

### 启动时注入
```bash
java -javaagent:./DynamicTracer-1.0-SNAPSHOT.jar -jar your-application.jar
```

### 运行时注入
使用 [jattach](https://github.com/jattach/jattach) 工具进行运行时注入：

```bash
# 下载并设置权限
chmod +x ./jattach

# 获取Java进程PID，然后注入Agent
./jattach <PID> load instrument false ./DynamicTracer-1.0-SNAPSHOT.jar
```

## 配置文件

### Hook规则配置 (dynamictracer-rules.yaml)
```yaml
# DynamicTracer 配置
# 规则按优先级顺序应用（数字越大，优先级越高，越先执行）

rules:
  - targetClass: javax.servlet.http.HttpServlet
    ruleClass: com.dynamictracer.rule.DynamicMethodHookRule
    priority: 30
    enabled: true
    parameters:
      enableReferenceTracking: true
      logLevel: INFO
```

### 方法Hook配置 (CSV格式)
CSV文件包含需要Hook的方法信息：
```csv
OwnerClass,FieldName,FieldType,TargetClass,TargetMethod,TargetMethodSignature
com.example.Service,userRepository,com.example.UserRepository,com.example.UserRepository,findById,(Ljava/lang/Long;)Lcom/example/User;
```

## 项目结构

```
src/main/java/com/dynamictracer/
├── Agent.java                          # Java Agent入口类
├── core/                              # 核心功能
│   ├── ClassModifier.java             # 类修改器接口
│   ├── DefaultTransformer.java        # 默认字节码转换器
│   ├── HighPerformanceSQLiteLogger.java # 高性能SQLite日志记录器
│   ├── ObjectReferenceTracker.java    # 对象引用追踪器
│   └── MethodBodyDumper.java          # 方法体转储工具
├── exception/                         # 异常类
│   └── BlacklistedClassException.java # 黑名单类异常
├── model/                            # 数据模型
│   └── MethodHookEntry.java          # 方法Hook条目
├── rule/                             # Hook规则
│   └── DynamicMethodHookRule.java    # 动态方法Hook规则
└── utils/                            # 工具类
    ├── CsvParser.java                # CSV解析器
    └── SpringBootApplicationFinder.java # Spring Boot应用查找器
```
