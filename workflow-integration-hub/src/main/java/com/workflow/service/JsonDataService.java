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
import com.workflow.dto.MemorySearchResponse;
import com.workflow.dto.MemorySearchRequest;
import com.workflow.dto.MemorySearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Autowired
    private MemorySearchService memorySearchService;

    public Object readJsonData(JsonDataRequest request) {
        String jsonBaseDir = config.getData().getJsonBaseDir();
        log.info("JSON_BASE_DIR配置值: {}", jsonBaseDir);
        
        try {
            // 0. 如有需要，先执行一次内存搜索（JOSEarcher），将结果文件准备好
            MemorySearchResponse memorySearchResponse = null;
            if (request.shouldRunMemorySearch()) {
                memorySearchResponse = runMemorySearchIfConfigured(request);
            }

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

            // 基于映射文件 + JOSE 内存搜索结果，为每条记录添加内存搜索结果
            log.info("开始为 JSON 数据添加内存搜索结果（如有可用）...");
            jsonData = enrichWithMemoryResults(jsonData);

            // 如果执行了内存搜索，则将 JSON 数据与内存搜索结果一起返回，方便前端统一消费
            if (memorySearchResponse != null) {
                ObjectNode wrapper = objectMapper.createObjectNode();
                wrapper.set("data", jsonData);
                wrapper.set("memorySearch", objectMapper.valueToTree(memorySearchResponse));
                return wrapper;
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
     * 根据 JsonDataRequest 构造并执行一次内存搜索（JOSEarcher）
     */
    private MemorySearchResponse runMemorySearchIfConfigured(JsonDataRequest request) {
        String targetClass = request.getMemoryTargetClass();
        String targetMethod = request.getMemoryTargetMethod();

        if (targetClass == null || targetClass.trim().isEmpty() ||
            targetMethod == null || targetMethod.trim().isEmpty()) {
            log.warn("runMemorySearch=true 但未提供 memoryTargetClass / memoryTargetMethod，跳过内存搜索");
            return null;
        }

        String searchId = (request.getMemorySearchId() != null && !request.getMemorySearchId().trim().isEmpty())
                ? request.getMemorySearchId()
                : "bp_search";

        MemorySearchRequest msRequest = MemorySearchRequest.builder()
                .targetClass(targetClass)
                .targetMethod(targetMethod)
                .searchId(searchId)
                // 其余参数使用 MemorySearchRequest 的默认值（AUTO 启动检测，GET /a.css 触发等）
                .build();

        log.info("在 read-json-data 流程中触发内存搜索, targetClass={}, targetMethod={}, searchId={}",
                targetClass, targetMethod, searchId);

        try {
            return memorySearchService.executeMemorySearch(msRequest);
        } catch (Exception e) {
            log.error("在 read-json-data 前执行内存搜索时发生错误", e);
            return MemorySearchResponse.builder()
                    .success(false)
                    .error("内存搜索失败: " + e.getMessage())
                    .targetClass(targetClass)
                    .targetMethod(targetMethod)
                    .searchId(searchId)
                    .build();
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

    /**
     * 为 JSON 数据中的每条记录添加内存搜索结果。
     *
     * 关联规则（依赖 memory_search_mapping.jsonl）：
     *   - 从 workflow.data.jsonBaseDir 目录中读取 mapping 文件 memory_search_mapping.jsonl
     *   - 每行映射包含：
     *       searchId, targetClass, targetMethod, ownerClass, resultFile
     *   - 对于 JSON 记录中 (CLASS_NAME, METHOD_NAME, OWNER_CLASS) = (targetClass, targetMethod, ownerClass) 的，
     *     读取对应的 resultFile（*.jsonl）中的 MemorySearchResult 列表，挂载到 MEMORY_RESULTS 字段。
     */
    private JsonNode enrichWithMemoryResults(JsonNode jsonData) {
        if (!jsonData.isArray()) {
            log.warn("JSON 数据不是数组，无法按记录级别添加内存搜索结果");
            return jsonData;
        }

        Map<String, List<MemorySearchResult>> memoryResultsByKey = loadMemoryResultsByKey();
        if (memoryResultsByKey.isEmpty()) {
            log.info("未在 JSON 目录中发现任何可用的内存搜索结果映射，跳过 MEMORY_RESULTS 富集。");
            return jsonData;
        }

        ArrayNode arrayNode = (ArrayNode) jsonData;
        int enrichedCount = 0;

        for (JsonNode record : arrayNode) {
            if (!record.isObject()) {
                continue;
            }
            ObjectNode objectRecord = (ObjectNode) record;
            JsonNode classNameNode = objectRecord.get("CLASS_NAME");
            JsonNode methodNameNode = objectRecord.get("METHOD_NAME");
            JsonNode ownerClassNode = objectRecord.get("OWNER_CLASS");
            if (classNameNode == null || classNameNode.isNull()) {
                continue;
            }
            if (methodNameNode == null || methodNameNode.isNull()) {
                continue;
            }
            if (ownerClassNode == null || ownerClassNode.isNull()) {
                continue;
            }

            String fullClassName = classNameNode.asText();
            String methodName = methodNameNode.asText();
            String ownerClass = ownerClassNode.asText();
            String key = buildMemoryResultKey(fullClassName, methodName, ownerClass);
            List<MemorySearchResult> results = memoryResultsByKey.get(key);

            if (results != null && !results.isEmpty()) {
                // 为避免 JsonNode 重复挂载，使用 valueToTree 每次生成新的 ArrayNode
                ArrayNode resultArray = objectMapper.valueToTree(results);
                objectRecord.set("MEMORY_RESULTS", resultArray);
                enrichedCount++;
            } else {
                // 即使没有结果，也可以选择返回空数组，便于前端统一处理
                if (!objectRecord.has("MEMORY_RESULTS")) {
                    objectRecord.putArray("MEMORY_RESULTS");
                }
            }
        }

        log.info("成功为 {} 条记录添加了内存搜索结果信息，涉及 {} 个不同的 (CLASS_NAME, METHOD_NAME, OWNER_CLASS) 组合",
                enrichedCount, memoryResultsByKey.size());
        return arrayNode;
    }

    /**
     * 从 jsonBaseDir 目录加载映射文件 + 所有 *_result.jsonl 文件，
     * 将其中的 MemorySearchResult 按 (CLASS_NAME, METHOD_NAME, OWNER_CLASS) 组合分组。
     *
     * 映射文件每行 JSON 字段：
     *   - searchId: bp_search_14
     *   - targetClass: org.springframework.http.HttpHeaders
     *   - targetMethod: add
     *   - ownerClass: <OWNER_CLASS>
     *   - resultFile: bp_search_14-HttpHeaders_result.jsonl
     */
    private Map<String, List<MemorySearchResult>> loadMemoryResultsByKey() {
        Map<String, List<MemorySearchResult>> resultMap = new HashMap<>();

        try {
            String jsonDir = config.getData().getJsonBaseDir();
            File dir = new File(jsonDir);
            if (!dir.exists() || !dir.isDirectory()) {
                log.warn("内存搜索结果目录不存在或不是目录: {}", jsonDir);
                return resultMap;
            }

            File mappingFile = new File(dir, "memory_search_mapping.jsonl");
            if (!mappingFile.exists()) {
                log.info("未找到内存搜索映射文件: {}", mappingFile.getAbsolutePath());
                return resultMap;
            }

            loadMappingsAndResults(mappingFile, dir, resultMap);

            log.info("加载内存搜索结果完成，共 {} 个 (CLASS_NAME, METHOD_NAME, OWNER_CLASS) 组合有结果",
                    resultMap.size());
        } catch (Exception e) {
            log.warn("加载内存搜索结果时发生错误", e);
        }

        return resultMap;
    }

    private void loadMappingsAndResults(File mappingFile, File dir,
                                        Map<String, List<MemorySearchResult>> resultMap) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(mappingFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String targetClass = textOrNull(node, "targetClass");
                    String targetMethod = textOrNull(node, "targetMethod");
                    String ownerClass = textOrNull(node, "ownerClass");
                    String resultFileName = textOrNull(node, "resultFile");

                    if (targetClass == null || targetMethod == null || ownerClass == null || resultFileName == null) {
                        continue;
                    }

                    String key = buildMemoryResultKey(targetClass, targetMethod, ownerClass);
                    File resultFile = new File(dir, resultFileName);
                    if (!resultFile.exists()) {
                        log.warn("映射中指定的结果文件不存在: {}", resultFile.getAbsolutePath());
                        continue;
                    }

                    List<MemorySearchResult> list = resultMap.computeIfAbsent(key, k -> new ArrayList<>());
                    loadResultsFromFile(resultFile, list);
                } catch (JsonProcessingException e) {
                    log.warn("解析内存搜索映射行失败 (file={}): {}", mappingFile.getName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("读取内存搜索映射文件失败: {}", mappingFile.getAbsolutePath(), e);
        }
    }

    private void loadResultsFromFile(File file, List<MemorySearchResult> targetList) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    MemorySearchResult result = objectMapper.readValue(line, MemorySearchResult.class);
                    targetList.add(result);
                } catch (JsonProcessingException e) {
                    log.warn("解析内存搜索结果行失败 (file={}): {}", file.getName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("读取内存搜索结果文件失败: {}", file.getAbsolutePath(), e);
        }
    }

    private String buildMemoryResultKey(String className, String methodName, String ownerClass) {
        return className + "#" + methodName + "#" + ownerClass;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(fieldName);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.trim().isEmpty()) ? null : s;
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