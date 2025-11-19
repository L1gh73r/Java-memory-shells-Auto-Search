package com.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WorkflowConfig;
import com.workflow.dto.MemorySearchRequest;
import com.workflow.dto.MemorySearchResponse;
import com.workflow.dto.MemorySearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MemorySearchService {

    @Autowired
    private ObjectMapper objectMapper;

    // @Autowired
    // private WorkflowConfig config;  // 暂时未使用，后续可用于配置路径
    private static final String JOS_RESULT_DIR = "atools/agent/jos_result";
    private static final String DYNAMIC_TRACER_JAR = "atools/agent/DynamicTracer-1.0-SNAPSHOT.jar";
    // 基础配置文件（通常为只读的模板，由宿主机挂载进来）
    private static final String BREAKPOINT_CONFIG_BASE_PATH = "atools/agent/config/breakpoint-config.json";
    // 运行时配置文件，每次内存搜索时生成，传给 DynamicTracer 使用
    private static final String BREAKPOINT_CONFIG_FULL_PATH = "atools/agent/config/breakpoint-config.runtime.json";
    private static final String TARGET_APP_JAR = "atools/benchmarks/druid_filter_mem-0.0.1-SNAPSHOT.jar";

    public MemorySearchResponse executeMemorySearch(MemorySearchRequest request) {
        long startTime = System.currentTimeMillis();
        String resultFileName = null;
        
        try {
            // 1. 验证输入参数
            if (request.getTargetClass() == null || request.getTargetClass().trim().isEmpty()) {
                return MemorySearchResponse.builder()
                        .success(false)
                        .error("target_class参数是必需的")
                        .build();
            }
            
            if (request.getTargetMethod() == null || request.getTargetMethod().trim().isEmpty()) {
                return MemorySearchResponse.builder()
                        .success(false)
                        .error("target_method参数是必需的")
                        .build();
            }

            // 2. 备份原始配置文件
            backupBreakpointConfig();

            // 3. 更新断点配置文件
            updateBreakpointConfig(request);

            // 4. 清理之前的结果文件
            cleanPreviousResults();

            // 5. 执行Java应用进行内存搜索
            String applicationOutput = executeJavaApplicationWithStartupDetection(request);

            // 6. 读取搜索结果
            resultFileName = generateResultFileName(request);
            List<MemorySearchResult> searchResults = readSearchResults(resultFileName);

            // 7. 恢复原始配置文件
            restoreBreakpointConfig();

            long executionTime = System.currentTimeMillis() - startTime;

            // 在控制台打印应用输出
            log.info("=== Java应用执行输出 ===");
            log.info(applicationOutput);
            log.info("=== 输出结束 ===");

            return MemorySearchResponse.builder()
                    .success(true)
                    .targetClass(request.getTargetClass())
                    .targetMethod(request.getTargetMethod())
                    .searchId(request.getSearchId())
                    .executionTimeMs(executionTime)
                    .resultFilePath(JOS_RESULT_DIR + "/" + resultFileName)
                    .searchResults(convertToMapList(searchResults))
                    .resultCount(searchResults.size())
                    .configUpdated(true)
                    .build();

        } catch (Exception e) {
            log.error("执行内存搜索时发生错误", e);
            long executionTime = System.currentTimeMillis() - startTime;
            
            try {
                restoreBreakpointConfig();
            } catch (Exception restoreError) {
                log.error("恢复配置文件时发生错误", restoreError);
            }

            return MemorySearchResponse.builder()
                    .success(false)
                    .error("内存搜索执行失败: " + e.getMessage())
                    .targetClass(request.getTargetClass())
                    .targetMethod(request.getTargetMethod())
                    .executionTimeMs(executionTime)
                    .build();
        }
    }

    /**
     * 初始化运行时断点配置文件：
     * - 如果存在基础模板（BREAKPOINT_CONFIG_BASE_PATH），先拷贝一份到运行时路径
     * - 如果不存在，则后续直接覆盖写运行时文件
     *
     * 注意：基础模板通常是宿主机挂载进来的文件，尽量避免直接修改它，
     * 因此我们始终只对 runtime 路径进行写入与删除操作。
     */
    private void backupBreakpointConfig() throws IOException {
        Path basePath = Paths.get(BREAKPOINT_CONFIG_BASE_PATH);
        Path runtimePath = Paths.get(BREAKPOINT_CONFIG_FULL_PATH);

        if (Files.exists(basePath)) {
            Files.copy(basePath, runtimePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("已基于模板初始化运行时断点配置: {}", runtimePath);
        } else {
            log.info("未找到基础断点配置模板: {}，将直接写入运行时配置文件", basePath);
        }
    }

    /**
     * 清理运行时断点配置文件。
     * 不再尝试覆盖基础模板文件，避免对宿主机挂载的只读文件进行写操作。
     */
    private void restoreBreakpointConfig() throws IOException {
        Path runtimePath = Paths.get(BREAKPOINT_CONFIG_FULL_PATH);

        if (Files.exists(runtimePath)) {
            Files.deleteIfExists(runtimePath);
            log.info("已删除运行时断点配置文件: {}", runtimePath);
        }
    }

    private void updateBreakpointConfig(MemorySearchRequest request) throws IOException {
        Map<String, Object> config = new HashMap<>();
        config.put("targetClass", request.getTargetClass());
        config.put("targetMethod", request.getTargetMethod());
        config.put("id", request.getSearchId());

        Path configPath = Paths.get(BREAKPOINT_CONFIG_FULL_PATH);
        String configJson = objectMapper.writeValueAsString(config);
        
        Files.write(configPath, configJson.getBytes(StandardCharsets.UTF_8));
        log.info("已更新断点配置: targetClass={}, targetMethod={}", 
                request.getTargetClass(), request.getTargetMethod());
    }

    private void cleanPreviousResults() {
        try {
            Path resultDir = Paths.get(JOS_RESULT_DIR);
            if (Files.exists(resultDir)) {
                Files.list(resultDir)
                        .filter(path -> path.toString().endsWith(".jsonl"))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                                log.debug("已删除旧结果文件: {}", path);
                            } catch (IOException e) {
                                log.warn("删除文件失败: {}", path, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("清理之前的结果文件时发生错误", e);
        }
    }

    private String executeJavaApplicationWithStartupDetection(MemorySearchRequest request) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                "java",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.logging/java.util.logging=ALL-UNNAMED",
                "-javaagent:" + DYNAMIC_TRACER_JAR,
                "-Ddynamictracer.breakpoint.config=" + BREAKPOINT_CONFIG_FULL_PATH,
                "-Ddynamictracer.breakpoint.enabled=true",
                "-Ddynamictracer.josearcher.output.path=" + JOS_RESULT_DIR,
                "-Dctfagent.csv.path=/dev/null",
                "-jar", TARGET_APP_JAR
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File("."));
        processBuilder.redirectErrorStream(true);

        log.info("执行命令: {}", String.join(" ", command));

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        
        // 异步读取输出
        CompletableFuture<Void> outputReader = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("应用输出: {}", line);
                }
            } catch (IOException e) {
                log.error("读取应用输出时发生错误", e);
            }
        });

        try {
            // 1. 等待应用启动
            boolean startupSuccess = waitForApplicationStartupWithStrategy(output, request);
            
            if (!startupSuccess) {
                process.destroyForcibly();
                throw new RuntimeException("Java应用启动检测失败 (" + request.getStartupTimeoutSeconds() + "秒)");
            }

            log.info("Java应用启动成功，开始触发请求...");

            // 2. 触发请求以激活断点
            boolean triggerSuccess = triggerApplicationRequests(request);
            
            if (!triggerSuccess) {
                log.warn("触发请求失败，但继续等待搜索结果...");
            }

            // 3. 等待搜索结果生成
            boolean resultsReady = waitForSearchResults(request);
            
            if (!resultsReady) {
                log.warn("未检测到搜索结果文件生成");
            }

            // 4. 等待一段时间让搜索完成
            Thread.sleep(request.getTriggerDelaySeconds() * 1000);

            // 5. 停止应用
            process.destroyForcibly();
            
            // 等待输出读取完成
            outputReader.get(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            process.destroyForcibly();
            throw new RuntimeException("执行Java应用时发生错误: " + e.getMessage(), e);
        }

        String applicationOutput = output.toString();
        log.info("Java应用执行完成，输出长度: {}", applicationOutput.length());
        
        return applicationOutput;
    }

    private boolean waitForApplicationStartupWithStrategy(StringBuilder output, MemorySearchRequest request) {
        String strategy = request.getStartupDetectionStrategy().toUpperCase();
        log.info("使用启动检测策略: {}", strategy);

        switch (strategy) {
            case "AUTO":
                return waitForStartupAuto(output, request);
            case "PORT":
                return waitForStartupByPort(request);
            case "LOG_PATTERN":
                return waitForStartupByLogPattern(output, request);
            case "HTTP_CHECK":
                return waitForStartupByHttpCheck(request);
            case "FIXED_DELAY":
                return waitForStartupByFixedDelay(request);
            default:
                log.warn("未知的启动检测策略: {}，使用AUTO策略", strategy);
                return waitForStartupAuto(output, request);
        }
    }

    private boolean waitForStartupAuto(StringBuilder output, MemorySearchRequest request) {
        log.info("使用自动检测策略");
        
        // 1. 首先尝试端口检测（如果提供了端口）
        if (request.getTargetPort() != null) {
            log.info("尝试端口检测: {}", request.getTargetPort());
            if (waitForStartupByPort(request)) {
                return true;
            }
        }

        // 2. 尝试HTTP健康检查（如果提供了URL）
        if (request.getHealthCheckUrl() != null && !request.getHealthCheckUrl().trim().isEmpty()) {
            log.info("尝试HTTP健康检查: {}", request.getHealthCheckUrl());
            if (waitForStartupByHttpCheck(request)) {
                return true;
            }
        }

        // 3. 尝试通用日志模式检测
        log.info("尝试通用日志模式检测");
        if (waitForStartupByCommonLogPatterns(output, request)) {
            return true;
        }

        // 4. 最后使用固定延迟
        log.info("使用固定延迟作为后备方案");
        return waitForStartupByFixedDelay(request);
    }

    private boolean waitForStartupByPort(MemorySearchRequest request) {
        if (request.getTargetPort() == null) {
            log.warn("端口检测策略需要指定targetPort参数");
            return false;
        }

        long startTime = System.currentTimeMillis();
        long timeoutMillis = request.getStartupTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", request.getTargetPort()), 1000);
                log.info("端口 {} 已开放，应用启动成功", request.getTargetPort());
                return true;
            } catch (IOException e) {
                // 端口未开放，继续等待
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("端口检测超时，端口 {} 未开放", request.getTargetPort());
        return false;
    }

    private boolean waitForStartupByLogPattern(StringBuilder output, MemorySearchRequest request) {
        if (request.getCustomLogPattern() == null || request.getCustomLogPattern().trim().isEmpty()) {
            log.warn("日志模式检测策略需要指定customLogPattern参数");
            return false;
        }

        Pattern customPattern = Pattern.compile(request.getCustomLogPattern(), Pattern.CASE_INSENSITIVE);
        long startTime = System.currentTimeMillis();
        long timeoutMillis = request.getStartupTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            String currentOutput = output.toString();
            
            if (customPattern.matcher(currentOutput).find()) {
                log.info("检测到自定义日志模式匹配");
                return true;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("自定义日志模式检测超时");
        return false;
    }

    private boolean waitForStartupByHttpCheck(MemorySearchRequest request) {
        String healthUrl = request.getHealthCheckUrl();
        if (healthUrl == null || healthUrl.trim().isEmpty()) {
            log.warn("HTTP检测策略需要指定healthCheckUrl参数");
            return false;
        }

        long startTime = System.currentTimeMillis();
        long timeoutMillis = request.getStartupTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                boolean isHealthy = sendHttpRequest(healthUrl, "GET", null);
                if (isHealthy) {
                    log.info("HTTP健康检查成功，应用启动完成");
                    return true;
                }
            } catch (Exception e) {
                // 健康检查失败，继续等待
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("HTTP健康检查超时");
        return false;
    }

    private boolean waitForStartupByFixedDelay(MemorySearchRequest request) {
        int delaySeconds = request.getFixedStartupDelaySeconds();
        log.info("使用固定延迟策略，等待 {} 秒", delaySeconds);
        
        try {
            Thread.sleep(delaySeconds * 1000L);
            log.info("固定延迟完成，假设应用已启动");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean waitForStartupByCommonLogPatterns(StringBuilder output, MemorySearchRequest request) {
        // 通用的应用启动标识模式
        Pattern[] commonPatterns = {
            // Spring Boot
            Pattern.compile(".*Started .* in .* seconds.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Tomcat started on port.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*JVM running for.*", Pattern.CASE_INSENSITIVE),
            
            // 通用Web服务器
            Pattern.compile(".*Server started.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Application started.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Listening on port.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Ready to accept connections.*", Pattern.CASE_INSENSITIVE),
            
            // Jetty
            Pattern.compile(".*Started Server.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Started @.*ms", Pattern.CASE_INSENSITIVE),
            
            // 其他常见模式
            Pattern.compile(".*Application startup complete.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Initialization completed.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Ready for requests.*", Pattern.CASE_INSENSITIVE)
        };

        long startTime = System.currentTimeMillis();
        long timeoutMillis = request.getStartupTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            String currentOutput = output.toString();
            
            for (Pattern pattern : commonPatterns) {
                if (pattern.matcher(currentOutput).find()) {
                    log.info("检测到通用启动完成标识");
                    return true;
                }
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("通用日志模式检测超时");
        return false;
    }

    private boolean triggerApplicationRequests(MemorySearchRequest request) {
        for (int i = 0; i < request.getMaxTriggerRetries(); i++) {
            try {
                log.info("发送触发请求 ({}/{}): {} {}", 
                        i + 1, request.getMaxTriggerRetries(), 
                        request.getTriggerMethod(), request.getTriggerUrl());

                boolean success = sendHttpRequest(
                    request.getTriggerUrl(), 
                    request.getTriggerMethod(), 
                    request.getTriggerBody()
                );

                if (success) {
                    log.info("触发请求成功");
                    Thread.sleep(request.getTriggerDelaySeconds() * 1000);
                    return true;
                }

                Thread.sleep(1000); // 重试前等待1秒
            } catch (Exception e) {
                log.warn("触发请求失败 (尝试 {}/{}): {}", i + 1, request.getMaxTriggerRetries(), e.getMessage());
            }
        }

        return false;
    }

    private boolean sendHttpRequest(String urlString, String method, String body) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method.toUpperCase());
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "MemorySearchService/1.0");

            if (body != null && !body.trim().isEmpty() && 
                ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int responseCode = connection.getResponseCode();
            log.debug("HTTP响应码: {}", responseCode);
            
            return responseCode >= 200 && responseCode < 400;

        } catch (Exception e) {
            log.debug("HTTP请求失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean waitForSearchResults(MemorySearchRequest request) {
        String expectedFileName = generateResultFileName(request);
        Path resultFile = Paths.get(JOS_RESULT_DIR, expectedFileName);
        
        // 等待最多10秒让结果文件生成
        for (int i = 0; i < 20; i++) {
            if (Files.exists(resultFile)) {
                log.info("检测到搜索结果文件: {}", resultFile);
                return true;
            }
            
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return false;
    }

    private String generateResultFileName(MemorySearchRequest request) {
        String className = request.getTargetClass();
        if (className.contains(".")) {
            className = className.substring(className.lastIndexOf(".") + 1);
        }
        return request.getSearchId() + "-" + className + "_result.jsonl";
    }

    private List<MemorySearchResult> readSearchResults(String fileName) throws IOException {
        Path resultFile = Paths.get(JOS_RESULT_DIR, fileName);
        List<MemorySearchResult> results = new ArrayList<>();

        if (!Files.exists(resultFile)) {
            log.warn("结果文件不存在: {}", resultFile);
            return results;
        }

        try (BufferedReader reader = Files.newBufferedReader(resultFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                try {
                    MemorySearchResult result = objectMapper.readValue(line, MemorySearchResult.class);
                    results.add(result);
                } catch (JsonProcessingException e) {
                    log.warn("解析结果行时发生错误: {}", line, e);
                }
            }
        }

        log.info("从文件 {} 读取到 {} 条搜索结果", fileName, results.size());
        return results;
    }

    private List<Map<String, Object>> convertToMapList(List<MemorySearchResult> results) {
        List<Map<String, Object>> mapList = new ArrayList<>();
        for (MemorySearchResult result : results) {
            try {
                String json = objectMapper.writeValueAsString(result);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(json, Map.class);
                mapList.add(map);
            } catch (JsonProcessingException e) {
                log.warn("转换搜索结果为Map时发生错误", e);
            }
        }
        return mapList;
    }
}
