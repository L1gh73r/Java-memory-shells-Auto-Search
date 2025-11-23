package com.decompiler;

import com.decompiler.config.BootJarConfig;
import com.decompiler.config.VineflowerOptions;
import com.decompiler.core.decompiler.VineflowerDecompiler;
import com.decompiler.core.extractor.JarExtractorImpl;
import com.decompiler.core.pom.PomExtractorImpl;
import com.decompiler.executor.ThreadPoolTaskExecutor;
import com.decompiler.service.BootJarProcessor;
import com.decompiler.service.BootJarProcessorImpl;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Options options = buildOptions();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                printHelp(formatter, options);
                return;
            }

            String input = cmd.getOptionValue("input");
            String output = cmd.getOptionValue("output");

            int threads = 8;
            if (cmd.hasOption("threads")) {
                try {
                    threads = Integer.parseInt(cmd.getOptionValue("threads"));
                    if (threads <= 0) {
                        logger.warn("线程数必须大于 0，使用默认值: {}",
                                Runtime.getRuntime().availableProcessors());
                        threads = Runtime.getRuntime().availableProcessors();
                    }
                } catch (NumberFormatException e) {
                    logger.warn("线程数参数解析失败，使用默认值: {}",
                            Runtime.getRuntime().availableProcessors());
                }
            }

            BootJarConfig config = new BootJarConfig.Builder()
                    .inputJarPath(input)
                    .outputPath(output)
                    .threadCount(threads)
                    .cleanWorkspace(true)
                    .vineflowerOptions(VineflowerOptions.getDefault())
                    .build();

            printConfiguration(config);

            executeDecompilation(config);

        } catch (MissingOptionException e) {
            logger.error("参数缺失: {}", e.getMessage());
            printHelp(formatter, options);
            System.exit(1);
        } catch (ParseException e) {
            logger.error("参数解析失败: {}", e.getMessage());
            printHelp(formatter, options);
            System.exit(1);
        } catch (Exception e) {
            logger.error("反编译过程中发生错误", e);
            System.exit(1);
        }
    }

    /**
     * 构建命令行选项
     */
    private static Options buildOptions() {
        Options options = new Options();

        options.addOption(Option.builder("i")
                .longOpt("input")
                .hasArg()
                .argName("path")
                .desc("输入 Spring Boot JAR 文件路径（必选）")
                .required()
                .build());

        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .argName("path")
                .desc("输出目录路径（必选）")
                .required()
                .build());

        options.addOption(Option.builder("t")
                .longOpt("threads")
                .hasArg()
                .argName("n")
                .desc("并发线程数（必选）")
                .required()
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("显示帮助信息")
                .required(false)
                .build());

        return options;
    }

    /**
     * 打印配置信息
     */
    private static void printConfiguration(BootJarConfig config) {
        logger.info("========================================");
        logger.info("Spring Boot JAR 反编译工具");
        logger.info("========================================");
        logger.info("输入文件: {}", config.getInputJarPath().getFileName());
        logger.info("输出目录: {}", config.getOutputPath());
        logger.info("线程数: {}", config.getThreadCount());
        logger.info("========================================");
    }

    private static void executeDecompilation(BootJarConfig config) {
        long startTime = System.currentTimeMillis();

        try {
            // 创建组件实例
            JarExtractorImpl jarExtractor = new JarExtractorImpl();
            VineflowerDecompiler decompiler = new VineflowerDecompiler(config.getVineflowerOptions());
            PomExtractorImpl pomExtractor = new PomExtractorImpl();
            ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor(config.getThreadCount());

            // 创建处理器
            BootJarProcessor processor = new BootJarProcessorImpl(
                    jarExtractor,
                    decompiler,
                    pomExtractor,
                    taskExecutor
            );

            // 执行处理
            processor.process(config);

            long endTime = System.currentTimeMillis();
            double totalTime = (endTime - startTime) / 1000.0;

            logger.info("========================================");
            logger.info("反编译完成！总耗时: {} 秒", String.format("%.2f", totalTime));
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("反编译失败", e);
            throw e;
        }
    }

    /**
     * 打印帮助信息
     */
    private static void printHelp(HelpFormatter formatter, Options options) {
        String header = "\nSpring Boot JAR 反编译工具\n\n";

        String footer = "\n示例:\n" +
                "  java -jar bootjar-decompiler.jar -i app.jar -o output -t 8\n" +
                "  java -jar bootjar-decompiler.jar --input app.jar --output output --threads 16\n";

        formatter.printHelp(
                "java -jar bootjar-decompiler.jar",
                header,
                options,
                footer,
                true
        );
    }
}