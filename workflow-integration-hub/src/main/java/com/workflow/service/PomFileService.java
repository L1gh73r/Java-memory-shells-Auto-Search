package com.workflow.service;

import com.workflow.config.WorkflowConfig;
import com.workflow.dto.PomFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class PomFileService {

    @Autowired
    private WorkflowConfig config;

    /**
     * 从容器中挂载进来的“目标应用 JAR”中提取最外层 pom.xml：
     * - 先在 jarInputDir 下查找 JAR 文件（文件名可能变化）
     * - 再在 JAR 内部查找路径层级最浅的 pom.xml（通常是 META-INF/maven/.../pom.xml）
     */
    public PomFileResponse readRootPom() {
        String jarInputDir = config.getData().getJarInputDir();

        if (jarInputDir == null || jarInputDir.trim().isEmpty()) {
            String error = "未配置 workflow.data.jar-input-dir，无法定位目标应用 JAR";
            log.warn(error);
            return PomFileResponse.builder()
                    .success(false)
                    .error(error)
                    .build();
        }

        try {
            Path jarDir = Paths.get(jarInputDir);
            if (!Files.exists(jarDir) || !Files.isDirectory(jarDir)) {
                String error = "jar-input-dir 不存在或不是目录: " + jarInputDir;
                log.warn(error);
                return PomFileResponse.builder()
                        .success(false)
                        .error(error)
                        .build();
            }

            Path jarPath = findTargetJar(jarDir);
            if (jarPath == null) {
                String error = "在 jar-input-dir 下未找到任何 .jar 文件: " + jarInputDir;
                log.warn(error);
                return PomFileResponse.builder()
                        .success(false)
                        .error(error)
                        .build();
            }

            return extractPomFromJar(jarPath);
        } catch (Exception e) {
            log.error("从 JAR 中提取最外层 pom.xml 时发生错误, jarInputDir={}", jarInputDir, e);
            return PomFileResponse.builder()
                    .success(false)
                    .error("从 JAR 中提取最外层 pom.xml 时发生错误: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 在给定目录下查找目标 JAR：
     * - 优先使用目录根下的 .jar 文件
     * - 如有多个，按文件名排序后取第一个，并打印告警日志
     */
    private Path findTargetJar(Path jarDir) throws Exception {
        try (Stream<Path> stream = Files.list(jarDir)) {
            List<Path> jars = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());

            if (jars.isEmpty()) {
                return null;
            }

            if (jars.size() > 1) {
                log.warn("在 {} 下发现多个 JAR 文件，将使用按文件名排序后的第一个: jars={}", jarDir, jars);
            }

            return jars.get(0);
        }
    }

    /**
     * 在 JAR 内查找路径层级最浅的 pom.xml，并读取内容。
     */
    private PomFileResponse extractPomFromJar(Path jarPath) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Optional<JarEntry> pomEntryOpt = jarFile.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().toLowerCase().endsWith("pom.xml"))
                    .min(Comparator.comparingInt(e -> e.getName().split("/").length));

            if (pomEntryOpt.isEmpty()) {
                String error = "在 JAR 中未找到任何 pom.xml: " + jarPath;
                log.info(error);
                return PomFileResponse.builder()
                        .success(false)
                        .error(error)
                        .build();
            }

            JarEntry pomEntry = pomEntryOpt.get();
            String content = new String(jarFile.getInputStream(pomEntry).readAllBytes(), StandardCharsets.UTF_8);

            String logicalPath = jarPath + "!/" + pomEntry.getName();
            log.info("从 JAR 中提取到最外层 pom.xml: {}", logicalPath);

            return PomFileResponse.builder()
                    .success(true)
                    .content(content)
                    .filePath(logicalPath)
                    .build();
        }
    }
}
