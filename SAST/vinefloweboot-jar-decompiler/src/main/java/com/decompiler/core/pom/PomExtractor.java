package com.decompiler.core.pom;

import java.nio.file.Path;

/**
 * POM 提取器接口
 */
public interface PomExtractor {
    /**
     * 从已解压的目录中提取 POM 文件
     * @param directory 目录路径
     * @param outputDir 输出目录
     */
    void extractFromDirectory(Path directory, Path outputDir);
}