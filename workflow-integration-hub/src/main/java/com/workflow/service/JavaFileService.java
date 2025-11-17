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
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

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

        return config.getJavaFile().getCommonSourceDirs().stream()
                .map(sourceDir -> Paths.get(baseDir, sourceDir, packagePath, className + ".java").toString())
                .collect(Collectors.toList());
    }
}