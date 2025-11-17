# Java Decompiler
一个基于 Vineflower 引擎的多线程 Java 批量反编译工具，支持快速处理大量的 class 文件和 JAR 文件。

## 功能特点

- 多线程处理：利用多线程处理反编译以提高效率
- 灵活配置：支持配置 Vineflower 反编译引擎的高级选项
- 批量处理：一次性处理成千上万的类文件
- 自动清理：可选择反编译成功后删除原始 class 文件
- **Spring Boot 支持**：自动识别并处理 Spring Boot Fat JAR，可选择是否反编译依赖库
- **CodeQL 友好**：专为 CodeQL 静态分析优化，将所有依赖库也反编译成源码

## 使用方法

### 基本用法
```java
DecompilerConfig config = new DecompilerConfig.Builder()
        .inputPath("/xxxx/code")
        .outputPath("/xxxx/output")
        .threadCount(8)
        .deleteClassFiles(true)
        .build();
OptionsBuilder optionsBuilder = new OptionsBuilder();
Map<String, Object> vineflowerOptions = optionsBuilder.build();
Decompiler decompiler = new Decompiler(config, vineflowerOptions);
long startTime = System.currentTimeMillis();
decompiler.execute();

long endTime = System.currentTimeMillis();
double totalTime = (endTime - startTime) / 1000.0;
logger.info("总耗时: " + totalTime + " 秒");
```

### Spring Boot JAR 反编译（含依赖库）
用于 CodeQL 静态分析的完整反编译：
```java
DecompilerConfig config = new DecompilerConfig.Builder()
        .inputPath("/path/to/springboot-app.jar")
        .outputPath("/path/to/output")
        .threadCount(8)
        .deleteClassFiles(true)
        .includeDependencies(true)  // 关键：包含依赖库反编译
        .build();

OptionsBuilder optionsBuilder = new OptionsBuilder();
Map<String, Object> vineflowerOptions = optionsBuilder.build();
Decompiler decompiler = new Decompiler(config, vineflowerOptions);
decompiler.execute();
```

输出目录结构：
```
output/
├── user-code/          # 用户代码（BOOT-INF/classes）
└── dependencies/       # 依赖库代码（BOOT-INF/lib/*.jar 反编译）
    ├── spring-core-x.x.x/
    ├── spring-boot-x.x.x/
    └── ...
```

### 使用示例
参考 `SpringBootDecompilerExample.java` 查看完整示例代码。

## 测试
混合场景下（JAR 和 class 放在一个目录中）共 43553 个文件，开启 8 线程用了 391.875 秒
```text
2025-04-27 22:26:57.191 INFO  文件总数：43552
2025-04-27 22:26:57.191 INFO  已成功反编译：43552
2025-04-27 22:26:57.191 INFO  失败：0
2025-04-27 22:26:57.192 INFO  总耗时: 391.875 秒
```

## 存在的问题

在 vineflower 反编译 class 文件的时候会有下面的情况：
```test
xxx_1.class -> xxx.java
```
会丢失下划线以及数字，这个 bug 目前还没有修复，如果有师傅们有解决方案可以提交 PR

## 一起完善
作者只是为了在学习和研究中方便操作才写了这个工具，如果存在 bug 和不合理的地方欢迎指正