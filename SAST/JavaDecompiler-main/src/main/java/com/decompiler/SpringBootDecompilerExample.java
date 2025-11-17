package com.decompiler;

import com.decompiler.config.DecompilerConfig;
import com.decompiler.config.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Spring Boot JAR 反编译示例
 * 使用方法：直接运行 main 方法，修改路径为你的 JAR 文件路径
 */
public class SpringBootDecompilerExample {
    private static final Logger logger = LoggerFactory.getLogger(SpringBootDecompilerExample.class);

    public static void main(String[] args) {
        try {
            // 配置输入输出路径
            String inputJar = "/Users/m1yuu/Code/showmaker/SAST/SAST_target/druid_filter_mem-0.0.1-SNAPSHOT.jar";
            String outputDir = "/Users/m1yuu/Code/showmaker/SAST/SAST_target/decompiled-with-deps";

            // 创建反编译配置
            DecompilerConfig config = new DecompilerConfig.Builder()
                    .inputPath(inputJar)
                    .outputPath(outputDir)
                    .threadCount(8)  // 使用 8 个线程
                    .deleteClassFiles(true)  // 反编译后删除 class 文件
                    .includeDependencies(true)  // 包含依赖库的反编译（重要！）
                    .build();

            // 配置 Vineflower 选项
            OptionsBuilder optionsBuilder = new OptionsBuilder();
            Map<String, Object> vineflowerOptions = optionsBuilder.build();

            // 创建反编译器并执行
            Decompiler decompiler = new Decompiler(config, vineflowerOptions);
            
            logger.info("========================================");
            logger.info("开始反编译 Spring Boot JAR");
            logger.info("输入: {}", inputJar);
            logger.info("输出: {}", outputDir);
            logger.info("包含依赖库: 是");
            logger.info("========================================");
            
            long startTime = System.currentTimeMillis();
            decompiler.execute();
            long endTime = System.currentTimeMillis();
            
            double totalTime = (endTime - startTime) / 1000.0;
            logger.info("总耗时: {} 秒", totalTime);
            
            logger.info("========================================");
            logger.info("反编译完成！");
            logger.info("用户代码目录: {}/user-code", outputDir);
            logger.info("依赖库代码目录: {}/dependencies", outputDir);
            logger.info("========================================");
            logger.info("");
            logger.info("下一步：使用 CodeQL 创建数据库");
            logger.info("  codeql database create {}/codeql-db \\", outputDir);
            logger.info("    --language=java \\");
            logger.info("    --source-root={} \\", outputDir);
            logger.info("    --build-mode=none");
            
        } catch (Exception e) {
            logger.error("反编译失败", e);
            System.exit(1);
        }
    }
}

