package com.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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