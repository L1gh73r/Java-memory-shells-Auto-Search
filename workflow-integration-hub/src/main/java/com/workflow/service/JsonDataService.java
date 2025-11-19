package com.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WorkflowConfig;
import com.workflow.dto.JsonDataRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;

@Service
@Slf4j
public class JsonDataService {

    @Autowired
    private WorkflowConfig config;

    @Autowired
    private ObjectMapper objectMapper;

    public Object readJsonData(JsonDataRequest request) {
        String jsonBaseDir = config.getData().getJsonBaseDir();
        log.info("JSON_BASE_DIR配置值: {}", jsonBaseDir);
        
        try {
            String actualFilePath = request.getActualFilePath();
            String fullPath = resolveFilePath(actualFilePath);
            log.info("请求路径: {}, 解析后完整路径: {}", actualFilePath, fullPath);
            
            File file = new File(fullPath);
            if (!file.exists()) {
                return Map.of(
                        "success", false,
                        "error", "JSON文件未找到: " + actualFilePath,
                        "searchPath", fullPath
                );
            }
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                return objectMapper.readTree(reader);
            }
        } catch (JsonProcessingException e) {
            log.error("JSON解析错误", e);
            return Map.of(
                    "success", false,
                    "error", "JSON格式错误: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("读取JSON文件时发生错误", e);
            return Map.of(
                    "success", false,
                    "error", "读取JSON文件时发生错误: " + e.getMessage()
            );
        }
    }

    private String resolveFilePath(String filePath) {
        if (Paths.get(filePath).isAbsolute()) {
            return filePath;
        } else {
            String jsonDir = config.getData().getJsonBaseDir();
            if (!filePath.toLowerCase().endsWith(".json")) {
                filePath += ".json";
            }
            return Paths.get(jsonDir, filePath).toString();
        }
    }
}