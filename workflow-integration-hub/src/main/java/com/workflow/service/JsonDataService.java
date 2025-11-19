package com.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.config.WorkflowConfig;
import com.workflow.dto.JavaFileRequest;
import com.workflow.dto.JavaFileResponse;
import com.workflow.dto.JsonDataRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class JsonDataService {

    @Autowired
    private WorkflowConfig config;

    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private JavaFileService javaFileService;

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
            
            JsonNode jsonData;
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                jsonData = objectMapper.readTree(reader);
            }
            
            // 如果需要包含源码，则为每条记录添加源码
            if (request.shouldIncludeSourceCode()) {
                log.info("开始为 JSON 数据添加源码...");
                jsonData = enrichWithSourceCode(jsonData);
            }
            
            return jsonData;
            
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
    
    /**
     * 为 JSON 数据中的每条记录添加源码
     */
    private JsonNode enrichWithSourceCode(JsonNode jsonData) {
        if (!jsonData.isArray()) {
            log.warn("JSON 数据不是数组，无法添加源码");
            return jsonData;
        }
        
        ArrayNode arrayNode = (ArrayNode) jsonData;
        Map<String, String> sourceCodeCache = new HashMap<>();
        int enrichedCount = 0;
        
        for (JsonNode record : arrayNode) {
            if (record.isObject()) {
                ObjectNode objectRecord = (ObjectNode) record;
                JsonNode classNameNode = objectRecord.get("CLASS_NAME");
                
                if (classNameNode != null && !classNameNode.isNull()) {
                    String className = classNameNode.asText();
                    
                    // 使用缓存避免重复读取相同的类
                    String sourceCode = sourceCodeCache.get(className);
                    if (sourceCode == null) {
                        sourceCode = readSourceCode(className);
                        sourceCodeCache.put(className, sourceCode);
                    }
                    
                    // 添加源码字段
                    if (sourceCode != null && !sourceCode.isEmpty()) {
                        objectRecord.put("SOURCE_CODE", sourceCode);
                        enrichedCount++;
                    } else {
                        objectRecord.put("SOURCE_CODE", "");
                        objectRecord.put("SOURCE_CODE_ERROR", "源码未找到");
                    }
                }
            }
        }
        
        log.info("成功为 {} 条记录添加了源码，缓存了 {} 个不同的类", enrichedCount, sourceCodeCache.size());
        return arrayNode;
    }
    
    /**
     * 读取指定类的源码
     */
    private String readSourceCode(String className) {
        try {
            JavaFileRequest javaFileRequest = JavaFileRequest.builder()
                    .packageClassName(className)
                    .build();
            
            JavaFileResponse response = javaFileService.readJavaFile(javaFileRequest);
            
            if (response.isSuccess() && response.getContent() != null) {
                return response.getContent();
            } else {
                log.debug("无法读取类 {} 的源码: {}", className, response.getError());
                return null;
            }
        } catch (Exception e) {
            log.error("读取类 {} 的源码时发生错误", className, e);
            return null;
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