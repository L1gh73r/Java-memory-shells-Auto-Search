# workflow-integration-hub 迁移设计文档

## 项目概述

将现有的Python Flask应用迁移为Java Spring Boot单体应用，保持原有功能的同时为后续的n8n集成功能扩展奠定基础。

## 现有Python应用分析

### 核心功能模块
1. **JavaFileReader** - Java源码文件读取
2. **CSVDataReader** - CSV文件分批读取处理  
3. **JSONFileReader** - JSON文件读取
4. **REST API端点** - 5个主要接口

### API接口清单
| 接口 | 方法 | 功能 |
|------|------|------|
| `/read-java-file` | POST | 根据包名+类名读取Java文件 |
| `/read-csv-data` | POST | 分批读取CSV数据 |
| `/read-json-data` | POST | 读取JSON文件 |
| `/health` | GET | 健康检查 |
| `/doc` | GET | 获取OpenAPI文档 |

### 现有配置环境变量
```python
JAVA_BASE_DIR = '/Users/yulate/data/work/rasp/benchmark/druid_filter_mem/src/main/external-deps'
CSV_BASE_DIR = '/Users/yulate/data/staticAnalysis/memory-find/dify-memory-shell-plugin/csv-data'
JSON_BASE_DIR = '/Users/yulate/data/staticAnalysis/memory-find/dify-memory-shell-plugin/json-data'
```

## Spring Boot项目结构设计

```
workflow-integration-hub/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/yourcompany/workflow/
│   │   │       ├── WorkflowIntegrationHubApplication.java  # 启动类
│   │   │       ├── controller/                             # REST控制器
│   │   │       │   ├── JavaFileController.java
│   │   │       │   ├── DataFileController.java
│   │   │       │   └── HealthController.java
│   │   │       ├── service/                                # 业务逻辑
│   │   │       │   ├── JavaFileService.java
│   │   │       │   ├── CsvDataService.java
│   │   │       │   └── JsonDataService.java
│   │   │       ├── dto/                                    # 数据传输对象
│   │   │       │   ├── JavaFileRequest.java
│   │   │       │   ├── CsvDataRequest.java
│   │   │       │   └── CommonResponse.java
│   │   │       ├── config/                                 # 配置类
│   │   │       │   ├── AppConfig.java
│   │   │       │   └── WebConfig.java
│   │   │       └── exception/                              # 异常处理
│   │   │           ├── GlobalExceptionHandler.java
│   │   │           └── BusinessException.java
│   │   └── resources/
│   │       ├── application.yml                             # 主配置
│   │       ├── application-dev.yml                         # 开发环境
│   │       ├── application-prod.yml                        # 生产环境
│   │       └── static/
│   │           └── api-docs.yaml                           # OpenAPI文档
│   └── test/
│       └── java/
├── pom.xml                                                # Maven配置
├── Dockerfile                                             # Docker构建
└── README.md
```

## 技术栈选择

### 核心依赖
```xml
<dependencies>
    <!-- Spring Boot Web Starter -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- 配置处理 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
    </dependency>
    
    <!-- 监控和健康检查 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- API文档 -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.0.2</version>
    </dependency>
    
    <!-- CSV处理 -->
    <dependency>
        <groupId>com.opencsv</groupId>
        <artifactId>opencsv</artifactId>
        <version>5.7.1</version>
    </dependency>
    
    <!-- 测试 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 关键配置文件

### application.yml
```yaml
server:
  port: 8080

spring:
  application:
    name: workflow-integration-hub
  
  jackson:
    default-property-inclusion: non_null
    date-format: yyyy-MM-dd HH:mm:ss

# 业务配置 (对应Python中的环境变量)
workflow:
  data:
    java-base-dir: ${JAVA_BASE_DIR:/Users/yulate/data/work/rasp/benchmark/druid_filter_mem/src/main/external-deps}
    csv-base-dir: ${CSV_BASE_DIR:/Users/yulate/data/staticAnalysis/memory-find/dify-memory-shell-plugin/csv-data}
    json-base-dir: ${JSON_BASE_DIR:/Users/yulate/data/staticAnalysis/memory-find/dify-memory-shell-plugin/json-data}
  
  csv:
    default-batch-size: 50
    max-batch-size: 1000
  
  java-file:
    common-source-dirs:
      - "src/main/java"
      - "src/java"
      - "src"
      - "java"
      - "."

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when_authorized

logging:
  level:
    com.yourcompany.workflow: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
```

## 核心代码实现示例

### 1. 启动类
```java
@SpringBootApplication
public class WorkflowIntegrationHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowIntegrationHubApplication.class, args);
    }
}
```

### 2. 配置类
```java
@Configuration
@ConfigurationProperties(prefix = "workflow")
@Data
public class WorkflowConfig {
    private DataConfig data = new DataConfig();
    private CsvConfig csv = new CsvConfig();
    private JavaFileConfig javaFile = new JavaFileConfig();

    @Data
    public static class DataConfig {
        private String javaBaseDir;
        private String csvBaseDir;
        private String jsonBaseDir;
    }

    @Data
    public static class CsvConfig {
        private int defaultBatchSize = 50;
        private int maxBatchSize = 1000;
    }

    @Data
    public static class JavaFileConfig {
        private List<String> commonSourceDirs = List.of("src/main/java", "src/java", "src", "java", ".");
    }
}
```

### 3. Java文件服务 (对应Python的JavaFileReader)
```java
@Service
@Slf4j
public class JavaFileService {
    
    @Autowired
    private WorkflowConfig config;
    
    public JavaFileResponse readJavaFile(JavaFileRequest request) {
        try {
            String[] parts = parsePackageClassName(request.getPackageClassName());
            String packageName = parts[0];
            String className = parts[1];
            
            List<String> possiblePaths = findJavaFilePaths(packageName, className);
            
            for (String filePath : possiblePaths) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    
                    return JavaFileResponse.builder()
                            .success(true)
                            .content(content)
                            .filePath(filePath)
                            .packageName(packageName)
                            .className(className)
                            .build();
                }
            }
            
            // 文件未找到
            return JavaFileResponse.builder()
                    .success(false)
                    .error("未找到Java文件: " + request.getPackageClassName())
                    .packageName(packageName)
                    .className(className)
                    .searchedPaths(possiblePaths)
                    .build();
                    
        } catch (Exception e) {
            log.error("读取Java文件时发生错误", e);
            return JavaFileResponse.builder()
                    .success(false)
                    .error("读取文件时发生错误: " + e.getMessage())
                    .build();
        }
    }
    
    private String[] parsePackageClassName(String packageClassName) {
        if (packageClassName == null || !packageClassName.contains(".")) {
            throw new IllegalArgumentException("包名+类名格式错误，应该包含至少一个点号");
        }
        
        int lastDotIndex = packageClassName.lastIndexOf('.');
        String packageName = packageClassName.substring(0, lastDotIndex);
        String className = packageClassName.substring(lastDotIndex + 1);
        
        return new String[]{packageName, className};
    }
    
    private List<String> findJavaFilePaths(String packageName, String className) {
        String packagePath = packageName.replace('.', File.separatorChar);
        String baseDir = config.getData().getJavaBaseDir();
        
        return config.getJavaFile().getCommonSourceDirs().stream()
                .map(sourceDir -> Paths.get(baseDir, sourceDir, packagePath, className + ".java").toString())
                .collect(Collectors.toList());
    }
}
```

### 4. CSV数据服务 (对应Python的CSVDataReader)
```java
@Service
@Slf4j
public class CsvDataService {
    
    @Autowired
    private WorkflowConfig config;
    
    public CsvDataResponse readCsvData(CsvDataRequest request) {
        try {
            String fullPath = resolveFilePath(request.getFilePath());
            File file = new File(fullPath);
            
            if (!file.exists()) {
                return CsvDataResponse.builder()
                        .success(false)
                        .error("CSV文件未找到: " + request.getFilePath())
                        .searchPath(fullPath)
                        .build();
            }
            
            // 统计总行数
            int totalRows = countDataRows(file);
            
            // 计算分页信息
            int batchSize = request.getBatchSize();
            int currentBatch = request.getCurrentBatch();
            int totalBatches = (totalRows + batchSize - 1) / batchSize;
            
            if (currentBatch >= totalBatches) {
                return CsvDataResponse.builder()
                        .success(false)
                        .error("批次索引无效。当前批次: " + currentBatch + ", 总批次数: " + totalBatches)
                        .build();
            }
            
            // 读取指定批次的数据
            List<CsvDataItem> data = readBatchData(file, batchSize, currentBatch);
            
            BatchInfo batchInfo = BatchInfo.builder()
                    .currentBatch(currentBatch)
                    .batchSize(batchSize)
                    .totalBatches(totalBatches)
                    .totalRows(totalRows)
                    .currentBatchSize(data.size())
                    .hasNextBatch(currentBatch < totalBatches - 1)
                    .hasPrevBatch(currentBatch > 0)
                    .build();
            
            return CsvDataResponse.builder()
                    .success(true)
                    .data(data)
                    .batchInfo(batchInfo)
                    .filePath(fullPath)
                    .build();
                    
        } catch (Exception e) {
            log.error("读取CSV文件时发生错误", e);
            return CsvDataResponse.builder()
                    .success(false)
                    .error("读取CSV文件时发生错误: " + e.getMessage())
                    .build();
        }
    }
    
    private String resolveFilePath(String filePath) {
        if (Paths.get(filePath).isAbsolute()) {
            return filePath;
        } else {
            String csvDir = config.getData().getCsvBaseDir();
            if (!filePath.toLowerCase().endsWith(".csv")) {
                filePath += ".csv";
            }
            return Paths.get(csvDir, filePath).toString();
        }
    }
    
    private List<CsvDataItem> readBatchData(File file, int batchSize, int currentBatch) throws IOException {
        List<CsvDataItem> data = new ArrayList<>();
        int startIndex = currentBatch * batchSize;
        int endIndex = startIndex + batchSize;
        int currentIndex = 0;
        int globalId = 1;
        
        try (CSVReader reader = new CSVReader(new FileReader(file))) {
            String[] nextLine;
            
            // 跳过标题行 (如果存在)
            String[] firstLine = reader.readNext();
            if (firstLine != null && firstLine.length >= 4) {
                if (!isHeaderRow(firstLine)) {
                    // 第一行不是标题，需要处理
                    if (currentIndex >= startIndex && currentIndex < endIndex) {
                        data.add(createCsvDataItem(firstLine, globalId++));
                    }
                    currentIndex++;
                }
            }
            
            while ((nextLine = reader.readNext()) != null && nextLine.length >= 4) {
                if (currentIndex >= startIndex && currentIndex < endIndex) {
                    data.add(createCsvDataItem(nextLine, globalId));
                }
                currentIndex++;
                globalId++;
                
                if (currentIndex >= endIndex) {
                    break;
                }
            }
        }
        
        return data;
    }
    
    private boolean isHeaderRow(String[] row) {
        if (row.length < 4) return false;
        String firstCol = row[0].toLowerCase().replace("\"", "");
        return firstCol.equals("ownerclass") || firstCol.equals("caller_class") || firstCol.equals("class");
    }
    
    private CsvDataItem createCsvDataItem(String[] row, int id) {
        return CsvDataItem.builder()
                .id(id)
                .callerClass(row[0].replace("\"", "").trim())
                .field(row[1].replace("\"", "").trim())
                .targetClass(row[2].replace("\"", "").trim())
                .method(row[3].replace("\"", "").trim())
                .build();
    }
}
```

### 5. REST控制器
```java
@RestController
@RequestMapping("/api")
@Slf4j
public class DataFileController {
    
    @Autowired
    private JavaFileService javaFileService;
    
    @Autowired
    private CsvDataService csvDataService;
    
    @Autowired
    private JsonDataService jsonDataService;
    
    @PostMapping("/read-java-file")
    public ResponseEntity<JavaFileResponse> readJavaFile(@RequestBody JavaFileRequest request) {
        if (request.getPackageClassName() == null || request.getPackageClassName().trim().isEmpty()) {
            JavaFileResponse response = JavaFileResponse.builder()
                    .success(false)
                    .error("packageClassName参数是必需的")
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
        
        JavaFileResponse response = javaFileService.readJavaFile(request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else if (response.getSearchedPaths() != null) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @PostMapping("/read-csv-data")
    public ResponseEntity<CsvDataResponse> readCsvData(@RequestBody CsvDataRequest request) {
        // 参数验证
        if (request.getFilePath() == null || request.getFilePath().trim().isEmpty()) {
            CsvDataResponse response = CsvDataResponse.builder()
                    .success(false)
                    .error("file_path参数是必需的")
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
        
        // 设置默认值
        if (request.getBatchSize() <= 0) {
            request.setBatchSize(50);
        }
        if (request.getCurrentBatch() < 0) {
            request.setCurrentBatch(0);
        }
        
        CsvDataResponse response = csvDataService.readCsvData(request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else if (response.getSearchPath() != null) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @PostMapping("/read-json-data")
    public ResponseEntity<?> readJsonData(@RequestBody JsonDataRequest request) {
        if (request.getFilePath() == null || request.getFilePath().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "file_path参数是必需的"
            ));
        }
        
        Object response = jsonDataService.readJsonData(request);
        return ResponseEntity.ok(response);
    }
}

@RestController
public class HealthController {
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = Map.of(
            "status", "healthy",
            "service", "Workflow Integration Hub"
        );
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/doc")
    public ResponseEntity<String> getApiDoc() {
        try {
            String content = Files.readString(
                Paths.get("src/main/resources/static/api-docs.yaml"), 
                StandardCharsets.UTF_8
            );
            return ResponseEntity.ok()
                    .header("Content-Type", "application/x-yaml")
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
```

## DTO类定义

### 请求/响应对象
```java
// 请求对象
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JavaFileRequest {
    private String packageClassName;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvDataRequest {
    private String filePath;
    private int batchSize = 50;
    private int currentBatch = 0;
}

// 响应对象
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JavaFileResponse {
    private boolean success;
    private String content;
    private String filePath;
    private String packageName;
    private String className;
    private String error;
    private List<String> searchedPaths;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvDataResponse {
    private boolean success;
    private List<CsvDataItem> data;
    private BatchInfo batchInfo;
    private String filePath;
    private String error;
    private String searchPath;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvDataItem {
    private int id;
    private String callerClass;
    private String field;
    private String targetClass;
    private String method;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchInfo {
    private int currentBatch;
    private int batchSize;
    private int totalBatches;
    private int totalRows;
    private int currentBatchSize;
    private boolean hasNextBatch;
    private boolean hasPrevBatch;
}
```

## 完整的Maven配置

### pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>
    
    <groupId>com.yourcompany</groupId>
    <artifactId>workflow-integration-hub</artifactId>
    <version>1.0.0</version>
    <name>workflow-integration-hub</name>
    <description>Workflow Integration Hub for Dify and n8n</description>
    
    <properties>
        <java.version>17</java.version>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.0.2</version>
        </dependency>
        
        <dependency>
            <groupId>com.opencsv</groupId>
            <artifactId>opencsv</artifactId>
            <version>5.7.1</version>
        </dependency>
        
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## 部署配置

### Dockerfile
```dockerfile
FROM openjdk:17-jdk-slim

WORKDIR /app

COPY target/workflow-integration-hub-1.0.0.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### docker-compose.yml
```yaml
version: '3.8'
services:
  workflow-hub:
    build: .
    ports:
      - "8080:8080"
    environment:
      - JAVA_BASE_DIR=/app/java-projects
      - CSV_BASE_DIR=/app/csv-data
      - JSON_BASE_DIR=/app/json-data
    volumes:
      - ${JAVA_BASE_DIR:-./java-projects}:/app/java-projects:ro
      - ${CSV_BASE_DIR:-./csv-data}:/app/csv-data:ro
      - ${JSON_BASE_DIR:-./json-data}:/app/json-data:ro
    restart: unless-stopped
```

## 迁移步骤

### 第一阶段：基础框架搭建
1. 创建Spring Boot项目
2. 配置Maven依赖
3. 实现基础的控制器和服务类
4. 配置文件迁移

### 第二阶段：核心功能迁移
1. 迁移JavaFileReader功能
2. 迁移CSVDataReader功能
3. 迁移JSONFileReader功能
4. API接口测试

### 第三阶段：完善和优化
1. 异常处理优化
2. 日志记录完善
3. API文档更新
4. 单元测试编写

### 第四阶段：部署和测试
1. Docker容器化
2. 环境配置验证
3. 性能测试
4. 功能验证

## 启动命令

### 开发环境
```bash
# Maven启动
mvn spring-boot:run

# 或者打包后启动
mvn clean package
java -jar target/workflow-integration-hub-1.0.0.jar
```

### 生产环境
```bash
# Docker启动
docker-compose up -d

# 或者直接运行
java -jar workflow-integration-hub-1.0.0.jar --spring.profiles.active=prod
```

## API访问

启动后，可以通过以下地址访问：

- **应用主页**: http://localhost:8080
- **健康检查**: http://localhost:8080/health
- **API文档**: http://localhost:8080/swagger-ui.html
- **OpenAPI规范**: http://localhost:8080/v3/api-docs

## 注意事项

1. **环境变量**: 确保配置正确的文件路径环境变量
2. **文件权限**: 确保应用有权限访问配置的目录
3. **端口冲突**: 默认端口8080，如有冲突请修改配置
4. **Java版本**: 需要Java 17或更高版本
5. **内存配置**: 大文件处理时可能需要调整JVM内存参数

这个设计保持了原有Python应用的所有功能，同时为后续的n8n集成扩展提供了良好的基础架构。
