package com.decompiler.core.extractor;

import com.decompiler.exception.ExtractionException;
import com.decompiler.util.PathUtils;
import com.decompiler.util.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class JarExtractorImpl implements JarExtractor {
    private static final Logger logger = LoggerFactory.getLogger(JarExtractorImpl.class);

    @Override
    public void extract(Path jarPath, Path targetDir) {
        try {
            logger.info("开始解压 JAR: {}", jarPath.getFileName());

            if (!Files.exists(jarPath)) {
                throw new ExtractionException("JAR 文件不存在: " + jarPath);
            }

            if (!ZipUtils.isJarFile(jarPath)) {
                throw new ExtractionException("不是有效的 JAR 文件: " + jarPath);
            }

            ZipUtils.unzip(jarPath, targetDir);

        } catch (IOException e) {
            throw new ExtractionException("解压 JAR 失败: " + jarPath, e);
        }
    }

    @Override
    public List<Path> listInnerJars(Path extractedDir) {
        List<Path> jarFiles = new ArrayList<>();

        try {
            // 查找 lib 目录（BOOT-INF/lib 或 WEB-INF/lib）
            Path libDir = PathUtils.resolveLibDirectory(extractedDir);

            if (libDir == null) {
                logger.warn("未找到 lib 目录，可能不是 Spring Boot JAR");
                return jarFiles;
            }

            logger.info("扫描依赖库: {}", libDir);

            // 遍历 lib 目录，找出所有 jar 文件
            try (Stream<Path> stream = Files.walk(libDir, 1)) {
                stream.filter(Files::isRegularFile)
                        .filter(ZipUtils::isJarFile)
                        .forEach(jarFiles::add);
            }

            logger.info("找到 {} 个依赖库", jarFiles.size());

        } catch (IOException e) {
            logger.error("扫描依赖库失败", e);
            throw new ExtractionException("扫描依赖库失败", e);
        }

        return jarFiles;
    }
}