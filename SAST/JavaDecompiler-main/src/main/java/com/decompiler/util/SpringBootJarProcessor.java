package com.decompiler.util;

import com.decompiler.model.DecompileJob;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Spring Boot JAR 专用处理器
 * 功能：
 * 1. 识别并处理 Spring Boot Fat JAR 结构
 * 2. 反编译用户代码（BOOT-INF/classes）
 * 3. 反编译所有依赖库（BOOT-INF/lib/*.jar）
 * 4. 将所有源码统一输出到源码目录，便于 CodeQL 分析
 */
public class SpringBootJarProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SpringBootJarProcessor.class);

    /**
     * 检查是否是 Spring Boot JAR
     */
    public static boolean isSpringBootJar(File jarFile) {
        if (!JarUtils.isJarFile(jarFile)) {
            return false;
        }

        try (JarFile jar = new JarFile(jarFile)) {
            // 检查是否包含 BOOT-INF 目录
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("BOOT-INF/")) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("检查 Spring Boot JAR 失败: {}", jarFile.getName(), e);
        }
        return false;
    }

    /**
     * 处理 Spring Boot JAR 文件
     * @param jarFile 输入的 Spring Boot JAR
     * @param outputPath 输出路径
     * @param includeLibs 是否包含依赖库的反编译
     * @return 所有需要反编译的 class 文件任务列表
     */
    public static List<DecompileJob> processSpringBootJar(File jarFile, String outputPath, boolean includeLibs) throws IOException {
        logger.info("========================================");
        logger.info("检测到 Spring Boot JAR: {}", jarFile.getName());
        logger.info("包含依赖库反编译: {}", includeLibs ? "是" : "否");
        logger.info("========================================");

        File outputDir = new File(outputPath);
        FileUtils.forceMkdir(outputDir);

        // 创建目录结构
        File userCodeDir = new File(outputDir, "user-code");  // 用户代码
        File libsCodeDir = new File(outputDir, "dependencies"); // 依赖库代码
        FileUtils.forceMkdir(userCodeDir);
        if (includeLibs) {
            FileUtils.forceMkdir(libsCodeDir);
        }

        List<DecompileJob> jobs = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile)) {
            // 步骤 1: 提取用户代码 (BOOT-INF/classes)
            logger.info("步骤 1: 提取用户代码 (BOOT-INF/classes)...");
            int userClassCount = extractUserClasses(jar, userCodeDir, jobs);
            logger.info("找到 {} 个用户代码 class 文件", userClassCount);

            // 步骤 2: 提取并处理依赖库 (BOOT-INF/lib/*.jar)
            if (includeLibs) {
                logger.info("步骤 2: 提取依赖库 JAR 文件 (BOOT-INF/lib)...");
                int libClassCount = extractAndProcessLibraries(jar, libsCodeDir, jobs);
                logger.info("从依赖库中找到 {} 个 class 文件", libClassCount);
            }
        }

        logger.info("========================================");
        logger.info("Spring Boot JAR 处理完成");
        logger.info("总共找到 {} 个待反编译的 class 文件", jobs.size());
        logger.info("用户代码输出目录: {}", userCodeDir.getAbsolutePath());
        if (includeLibs) {
            logger.info("依赖库代码输出目录: {}", libsCodeDir.getAbsolutePath());
        }
        logger.info("========================================");

        return jobs;
    }

    /**
     * 提取用户代码 (BOOT-INF/classes 中的 .class 文件)
     */
    private static int extractUserClasses(JarFile jar, File outputDir, List<DecompileJob> jobs) throws IOException {
        int count = 0;
        Enumeration<JarEntry> entries = jar.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            // 只处理 BOOT-INF/classes 下的 class 文件
            if (entryName.startsWith("BOOT-INF/classes/") && entryName.endsWith(".class")) {
                // 移除 "BOOT-INF/classes/" 前缀
                String relativePath = entryName.substring("BOOT-INF/classes/".length());
                File targetFile = new File(outputDir, relativePath);

                // 确保父目录存在
                FileUtils.forceMkdirParent(targetFile);

                // 提取文件
                try (InputStream in = jar.getInputStream(entry)) {
                    FileUtils.copyInputStreamToFile(in, targetFile);
                    
                    // 添加到反编译任务列表
                    jobs.add(new DecompileJob(targetFile, targetFile.getParent(), null));
                    count++;
                } catch (IOException e) {
                    logger.warn("提取用户代码文件失败: {} - {}", entryName, e.getMessage());
                }
            }
        }

        return count;
    }

    /**
     * 提取并处理依赖库 (BOOT-INF/lib/*.jar)
     */
    private static int extractAndProcessLibraries(JarFile jar, File libsOutputDir, List<DecompileJob> jobs) throws IOException {
        int totalClassCount = 0;
        List<String> dependencyJars = new ArrayList<>();

        // 第一步：收集所有依赖 JAR 的名称
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            if (entryName.startsWith("BOOT-INF/lib/") && entryName.endsWith(".jar") && !entry.isDirectory()) {
                dependencyJars.add(entryName);
            }
        }

        logger.info("找到 {} 个依赖库 JAR 文件", dependencyJars.size());

        // 第二步：提取并反编译每个依赖 JAR
        for (String jarEntryName : dependencyJars) {
            try {
                // 提取 JAR 文件到临时位置
                JarEntry jarEntry = jar.getJarEntry(jarEntryName);
                String jarName = new File(jarEntryName).getName();
                String jarBaseName = jarName.substring(0, jarName.lastIndexOf(".jar"));
                
                File tempJarFile = File.createTempFile("dep-", ".jar");
                tempJarFile.deleteOnExit();

                try (InputStream in = jar.getInputStream(jarEntry)) {
                    FileUtils.copyInputStreamToFile(in, tempJarFile);
                }

                // 处理依赖 JAR 中的 class 文件
                File libOutputDir = new File(libsOutputDir, jarBaseName);
                int classCount = extractClassesFromDependencyJar(tempJarFile, libOutputDir, jobs);
                totalClassCount += classCount;

                logger.debug("从 {} 提取了 {} 个 class 文件", jarName, classCount);

                // 清理临时文件
                tempJarFile.delete();

            } catch (Exception e) {
                logger.warn("处理依赖 JAR 失败: {} - {}", jarEntryName, e.getMessage());
            }
        }

        return totalClassCount;
    }

    /**
     * 从依赖 JAR 中提取 class 文件
     */
    private static int extractClassesFromDependencyJar(File jarFile, File outputDir, List<DecompileJob> jobs) throws IOException {
        int count = 0;
        FileUtils.forceMkdir(outputDir);

        try (JarFile depJar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = depJar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // 只处理 .class 文件，排除 module-info.class 和内部类
                if (entryName.endsWith(".class") && 
                    !entryName.contains("module-info") &&
                    !entry.isDirectory()) {
                    
                    File targetFile = new File(outputDir, entryName);

                    // 确保父目录存在
                    FileUtils.forceMkdirParent(targetFile);

                    // 提取文件
                    try (InputStream in = depJar.getInputStream(entry)) {
                        FileUtils.copyInputStreamToFile(in, targetFile);
                        
                        // 添加到反编译任务列表
                        jobs.add(new DecompileJob(targetFile, targetFile.getParent(), null));
                        count++;
                    } catch (IOException e) {
                        logger.debug("提取依赖库文件失败: {} - {}", entryName, e.getMessage());
                    }
                }
            }
        }

        return count;
    }
}

