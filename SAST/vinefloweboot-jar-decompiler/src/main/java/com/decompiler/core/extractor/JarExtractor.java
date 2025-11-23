package com.decompiler.core.extractor;

import java.nio.file.Path;
import java.util.List;

/**
 * JAR 解压器接口
 */
public interface JarExtractor {

    /**
     * 解压 JAR 文件到目标目录
     * @param jarPath JAR 文件路径
     * @param targetDir 目标目录
     */
    void extract(Path jarPath, Path targetDir);

    /**
     * 列出已解压目录中的所有内部 JAR 文件
     * @param extractedDir 已解压的目录
     * @return 内部 JAR 文件路径列表
     */
    List<Path> listInnerJars(Path extractedDir);
}