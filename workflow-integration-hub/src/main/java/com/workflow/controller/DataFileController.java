package com.workflow.controller;

import com.workflow.dto.CsvDataRequest;
import com.workflow.dto.CsvDataResponse;
import com.workflow.dto.JavaFileRequest;
import com.workflow.dto.JavaFileResponse;
import com.workflow.dto.JsonDataRequest;
import com.workflow.dto.PomFileResponse;
import com.workflow.dto.MemorySearchRequest;
import com.workflow.dto.MemorySearchResponse;
import com.workflow.service.CsvDataService;
import com.workflow.service.JavaFileService;
import com.workflow.service.JsonDataService;
import com.workflow.service.MemorySearchService;
import com.workflow.service.PomFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

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

    @Autowired
    private MemorySearchService memorySearchService;

    @Autowired
    private PomFileService pomFileService;

    @PostMapping("/read-java-file")
    public ResponseEntity<JavaFileResponse> readJavaFile(@RequestBody JavaFileRequest request) {
        log.info("收到 /api/read-java-file 请求, body={}", request);
        if (request.getPackageClassName() == null || request.getPackageClassName().trim().isEmpty()) {
            JavaFileResponse response = JavaFileResponse.builder()
                .success(false)
                .error("packageClassName参数是必需的")
                .build();
            return ResponseEntity.badRequest().body(response);
        }

        JavaFileResponse response = javaFileService.readJavaFile(request);

        // 为了方便前端和集成工具处理，这里统一返回 200 + JSON，
        // 即使未找到文件也通过 response.success=false + error 信息来表示。
        // 原先返回 404 时，n8n 等工具只看到空响应，不利于排查。
        return ResponseEntity.ok(response);
    }

    /**
     * 调试用：功能与 /read-java-file 一致，仅用于排查路由问题
     */
    @PostMapping("/read-java-file-debug")
    public ResponseEntity<JavaFileResponse> readJavaFileDebug(@RequestBody JavaFileRequest request) {
        log.info("收到 /api/read-java-file-debug 请求, body={}", request);
        return readJavaFile(request);
    }

    @PostMapping("/read-csv-data")
    public ResponseEntity<CsvDataResponse> readCsvData(@RequestBody CsvDataRequest request) {
        if (request.getFilePath() == null || request.getFilePath().trim().isEmpty()) {
            CsvDataResponse response = CsvDataResponse.builder()
                    .success(false)
                    .error("file_path参数是必需的")
                    .build();
            return ResponseEntity.badRequest().body(response);
        }

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
        String actualFilePath = request.getActualFilePath();
        if (actualFilePath == null || actualFilePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "file_path参数是必需的"
            ));
        }

        Object response = jsonDataService.readJsonData(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/memory-search")
    public ResponseEntity<MemorySearchResponse> executeMemorySearch(@RequestBody MemorySearchRequest request) {
        try {
            MemorySearchResponse response = memorySearchService.executeMemorySearch(request);
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            log.error("执行内存搜索时发生未处理的错误", e);
            MemorySearchResponse errorResponse = MemorySearchResponse.builder()
                    .success(false)
                    .error("内存搜索服务发生异常: " + e.getMessage())
                    .targetClass(request.getTargetClass())
                    .targetMethod(request.getTargetMethod())
                    .build();
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 读取反编译结果最外层的 pom.xml 文件内容。
     * 为了和其他接口保持一致，即使未找到也返回 200 + JSON，由 success 标识是否成功。
     */
    @GetMapping("/read-root-pom")
    public ResponseEntity<PomFileResponse> readRootPom() {
        PomFileResponse response = pomFileService.readRootPom();
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
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}