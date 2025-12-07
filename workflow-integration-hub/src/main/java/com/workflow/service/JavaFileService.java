package com.workflow.service;

import com.workflow.config.WorkflowConfig;
import com.workflow.dto.JavaFileRequest;
import com.workflow.dto.JavaFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class JavaFileService {

    @Autowired
    private WorkflowConfig config;

    /**
     * 基于 java-base-dir 预先构建的索引：
     *   key: Java 文件名（例如 "DefaultListableBeanFactory.java"）
     *   value: 该文件名对应的所有绝对路径列表
     *
     * 这样可以避免在每次查找时都对 baseDir 做一次 Files.walk，全量扫描文件系统。
     */
    private volatile Map<String, List<String>> javaFilesByName;

    /**
     * 在 workflow 启动时预热 Java 文件索引，把一次性的 Files.walk 成本提前到应用启动阶段，
     * 避免第一次调用 /api/read-json-data 时卡顿。
     */
    @PostConstruct
    public void warmUpIndex() {
        try {
            String baseDir = config.getData().getJavaBaseDir();
            log.info("预热 Java 文件索引, baseDir={}", baseDir);
            getJavaFilesByNameIndex(baseDir);
        } catch (Exception e) {
            log.warn("预热 Java 文件索引时发生错误，将在首次请求时按需构建索引。", e);
        }
    }

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
        String fileName = className + ".java";

        List<String> candidates = new ArrayList<>();

        // 1. 先按原有逻辑，基于 commonSourceDirs 构造一批可能路径（适合项目源码目录）
        List<String> directPaths = config.getJavaFile().getCommonSourceDirs().stream()
                .map(sourceDir -> Paths.get(baseDir, sourceDir, packagePath, fileName).toString())
                .collect(Collectors.toList());
        candidates.addAll(directPaths);

        // 2. 再做一轮递归搜索，适配 JavaDecompiler 的 user-code/dependencies 等结构
        //    目标：在 baseDir 下找到所有以 <packagePath>/<className>.java 结尾的文件。
        //    为避免每次都 Files.walk，这里通过 javaFilesByName 索引做一次性预扫描。
        try {
            Map<String, List<String>> index = getJavaFilesByNameIndex(baseDir);
            String expectedSuffix = packagePath + File.separator + fileName;

            List<String> walked = new ArrayList<>();
            List<String> byName = index.getOrDefault(fileName, Collections.emptyList());

            // 2.1 优先按包路径后缀精确匹配
            for (String p : byName) {
                if (p.endsWith(expectedSuffix)) {
                    walked.add(p);
                }
            }

            // 2.2 如果严格按包路径没有命中，再退一步，只按文件名匹配（适配一些特殊反编译目录）
            if (walked.isEmpty()) {
                walked.addAll(byName);
            }

            if (!walked.isEmpty()) {
                log.info("在 baseDir={} 下为 {}.{} 找到 {} 个匹配文件", baseDir, packageName, className, walked.size());
                candidates.addAll(walked);
            } else {
                log.info("在 baseDir={} 下未找到 {}.{} 的任何匹配 Java 文件", baseDir, packageName, className);
            }
        } catch (Exception e) {
            log.warn("基于索引查找 Java 文件时发生错误，baseDir={}", baseDir, e);
        }

        return candidates;
    }

    /**
     * 构建或返回已有的 java 文件索引（按文件名分组）。
     * 索引只依赖 java-base-dir，构建一次即可重复使用。
     */
    private Map<String, List<String>> getJavaFilesByNameIndex(String baseDir) {
        Map<String, List<String>> cache = javaFilesByName;
        if (cache != null) {
            return cache;
        }

        synchronized (this) {
            if (javaFilesByName != null) {
                return javaFilesByName;
            }

            Map<String, List<String>> index = new ConcurrentHashMap<>();
            try {
                Path basePath = Paths.get(baseDir);
                if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
                    log.warn("java-base-dir 不存在或不是目录: {}", baseDir);
                    javaFilesByName = index;
                    return index;
                }

                try (Stream<Path> stream = Files.walk(basePath)) {
                    stream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".java"))
                            .forEach(p -> {
                                String name = p.getFileName().toString();
                                String pathStr = p.toString();
                                index.computeIfAbsent(name, k -> new ArrayList<>()).add(pathStr);
                            });
                }

                int totalFiles = index.values().stream().mapToInt(List::size).sum();
                log.info("已在 baseDir={} 下构建 Java 文件索引：唯一文件名={}，总文件数={}",
                        baseDir, index.size(), totalFiles);
            } catch (Exception e) {
                log.warn("构建 Java 文件索引时发生错误，baseDir={}", baseDir, e);
            }

            javaFilesByName = index;
            return index;
        }
    }
}