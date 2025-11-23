package com.decompiler.core.pom;

import com.decompiler.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PomExtractorImpl implements PomExtractor {
    private static final Logger logger = LoggerFactory.getLogger(PomExtractorImpl.class);

    @Override
    public void extractFromDirectory(Path directory, Path outputDir) {
        try {
            logger.info("从解压目录提取 POM: {}", directory);

            if (!Files.exists(directory)) {
                logger.warn("目录不存在: {}", directory);
                return;
            }

            FileUtils.ensureDirectory(outputDir);

            // 查找 META-INF/maven/**/pom.xml
            Path metaInfMaven = directory.resolve("META-INF").resolve("maven");

            if (!Files.exists(metaInfMaven)) {
                logger.warn("未找到 META-INF/maven 目录");
                return;
            }

            // 递归查找第一个 pom.xml
            Files.walk(metaInfMaven)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .findFirst()
                    .ifPresent(pomFile -> {
                        try {
                            Path outputFile = outputDir.resolve("pom.xml");
                            FileUtils.copyFile(pomFile, outputFile);
                            logger.info("已提取 POM: {}", outputFile);
                        } catch (IOException e) {
                            logger.error("复制 POM 文件失败", e);
                        }
                    });

        } catch (IOException e) {
            logger.error("从目录提取 POM 失败: {}", directory, e);
        }
    }
}