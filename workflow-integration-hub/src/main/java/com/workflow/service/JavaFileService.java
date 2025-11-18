package com.workflow.service;

import com.workflow.config.WorkflowConfig;
import com.workflow.dto.JavaFileRequest;
import com.workflow.dto.JavaFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        //    目标：在 baseDir 下找到所有以 <packagePath>/<className>.java 结尾的文件
        try {
            Path basePath = Paths.get(baseDir);
            if (Files.exists(basePath) && Files.isDirectory(basePath)) {
                String expectedSuffix = packagePath + File.separator + fileName;

                try (Stream<Path> stream = Files.walk(basePath)) {
                    List<String> walked = stream
                            .filter(Files::isRegularFile)
                            .map(Path::toString)
                            .filter(p -> p.endsWith(expectedSuffix))
                            .collect(Collectors.toList());
                    candidates.addAll(walked);
                }
            }
        } catch (Exception e) {
            log.warn("递归搜索 Java 文件时发生错误，baseDir={}", baseDir, e);
        }

        return candidates;
    }
}