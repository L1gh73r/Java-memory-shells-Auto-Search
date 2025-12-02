package com.dynamictracer.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;


public class HighPerformanceSQLiteLogger {
    
    // 日志文件基础路径（不带扩展名）
    private final String dbPath;
    
    // 异步写入线程池（单线程保证写入顺序）
    private final ExecutorService writeExecutor;
    
    // 内存缓冲区
    private final BlockingQueue<HookLogEntry> logBuffer;
    
    // 批量写入大小（一次写多少条 JSON）
    private static final int BATCH_SIZE = 1000;
    
    // 缓冲区最大大小
    private static final int BUFFER_SIZE = 10000;
    
    // 控制标志
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicLong totalRecords = new AtomicLong(0);

    // JSON 日志写入
    private volatile java.io.FileWriter fallbackFileWriter;
    private final Object fallbackLock = new Object();
    private volatile boolean useFallbackLogging = false;
    // 控制 JSON 数组中的逗号
    private volatile boolean firstJsonRecord = true;
    
    /**
     * Hook日志条目
     */
    public static class HookLogEntry {
        public final long timestamp;
        public final String className;
        public final String methodName;
        public final String methodSignature;
        public final String ownerClass;
        public final String fieldName;
        public final String parameters;
        public final String stackTrace;
        public final String referenceInfo;
        public final String threadName;
        public final long threadId;
        
        public HookLogEntry(String className, String methodName, String methodSignature,
                           String ownerClass, String fieldName, String parameters, String stackTrace) {
            this.timestamp = System.currentTimeMillis();
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.ownerClass = ownerClass;
            this.fieldName = fieldName;
            this.parameters = parameters;
            this.stackTrace = stackTrace;
            this.referenceInfo = null; // 向后兼容，默认为null
            this.threadName = Thread.currentThread().getName();
            this.threadId = Thread.currentThread().getId();
        }
        
        public HookLogEntry(String className, String methodName, String methodSignature,
                           String ownerClass, String fieldName, String parameters, String stackTrace, String referenceInfo) {
            this.timestamp = System.currentTimeMillis();
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.ownerClass = ownerClass;
            this.fieldName = fieldName;
            this.parameters = parameters;
            this.stackTrace = stackTrace;
            this.referenceInfo = referenceInfo;
            this.threadName = Thread.currentThread().getName();
            this.threadId = Thread.currentThread().getId();
        }
    }
    
    public HighPerformanceSQLiteLogger() {
        // 支持通过系统属性自定义日志目录，默认放在当前工程下的 memory-find/logdata
        // -Dctfagent.db.dir=/absolute/path/to/logdir
        String defaultBaseDir = "./memory-find/logdata";
        String baseDir = System.getProperty("ctfagent.db.dir", defaultBaseDir);

        // 日志基础路径（不包含扩展名）
        this.dbPath = baseDir + "/ctf_hook_logs_" +
            new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        this.logBuffer = new LinkedBlockingQueue<>(BUFFER_SIZE);
        
        // 创建单线程写入器
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CTFAgent-JSON-Writer");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化 JSON 日志文件
        initializeJsonLogging();
        
        // 启动异步写入任务
        startAsyncWriter();
        
        // 注册shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        
        System.out.println("🚀 高性能JSON日志记录器已启动，日志路径: " + dbPath + ".json");
    }
    
    /**
     * 初始化 JSON 日志文件（写入开头的 "["）
     */
    private void initializeJsonLogging() {
        try {
            String jsonPath = dbPath + ".json";
            java.io.File logFile = new java.io.File(jsonPath);
            java.io.File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (parentDir.mkdirs()) {
                    System.out.println("✅ 创建日志目录: " + parentDir.getAbsolutePath());
                } else {
                    System.err.println("❌ 无法创建日志目录: " + parentDir.getAbsolutePath());
                }
            }
            
            synchronized (fallbackLock) {
                fallbackFileWriter = new java.io.FileWriter(jsonPath, false); // 覆盖写
                useFallbackLogging = true;
                firstJsonRecord = true;
                fallbackFileWriter.write("[\n");
                fallbackFileWriter.flush();
            }
            
            System.out.println("📝 JSON 日志文件已初始化: " + jsonPath);
        } catch (Exception e) {
            System.err.println("❌ 初始化 JSON 日志文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 记录hook日志（异步）
     */
    public void logHook(String className, String methodName, String methodSignature,
                       String ownerClass, String fieldName, String parameters, String stackTrace) {
        
        if (isShutdown.get()) {
            return;
        }
        
        HookLogEntry entry = new HookLogEntry(className, methodName, methodSignature,
                                            ownerClass, fieldName, parameters, stackTrace);
        
        // 尝试添加到缓冲区，如果满了就丢弃（避免内存溢出）
        if (!logBuffer.offer(entry)) {
            System.err.println("⚠️  日志缓冲区已满，丢弃日志记录: " + className + "." + methodName);
        } else {
            // 成功添加到缓冲区，输出调试信息（仅前几条）
            long currentCount = totalRecords.get() + logBuffer.size();
            if (currentCount <= 10) {
//                System.out.println("📝 日志已加入缓冲区: " + className + "." + methodName + " (缓冲区大小: " + logBuffer.size() + ")");
            }
        }
    }
    
    /**
     * 启动异步写入任务
     */
    private void startAsyncWriter() {
        writeExecutor.submit(() -> {
            List<HookLogEntry> batch = new ArrayList<>(BATCH_SIZE);
            
            while (!isShutdown.get() || !logBuffer.isEmpty()) {
                try {
                    // 收集批量数据
                    HookLogEntry entry = logBuffer.poll(100, TimeUnit.MILLISECONDS);
                    if (entry != null) {
                        batch.add(entry);
                        
                        // 快速收集更多数据直到批量大小
                        logBuffer.drainTo(batch, BATCH_SIZE - 1);
                    }
                    
                    // 如果有数据则写入
                    if (!batch.isEmpty()) {
                        writeBatch(batch);
                        batch.clear();
                    }
                    
                } catch (InterruptedException e) {
                    if (!isShutdown.get()) {
                        System.err.println("异步写入线程被中断: " + e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    System.err.println("异步写入出错: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            System.out.println("异步写入线程已结束");
        });
    }
    
    /**
     * 批量写入 JSON 日志文件
     */
    private void writeBatch(List<HookLogEntry> entries) {
        if (entries.isEmpty() || !useFallbackLogging || fallbackFileWriter == null) {
            return;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

            synchronized (fallbackLock) {
                for (HookLogEntry entry : entries) {
                    long id = totalRecords.incrementAndGet();

                    if (!firstJsonRecord) {
                        fallbackFileWriter.write(",\n");
                    } else {
                        firstJsonRecord = false;
                    }

                    String dateTime = sdf.format(new Date(entry.timestamp));
                    String createdAt = dateTime;

                    StringBuilder sb = new StringBuilder();
                    sb.append("  {\n");
                    sb.append("    \"ID\": ").append(id).append(",\n");
                    sb.append("    \"TIMESTAMP\": ").append(entry.timestamp).append(",\n");
                    sb.append("    \"DATE_TIME\": ").append(escapeJson(dateTime)).append(",\n");
                    sb.append("    \"CLASS_NAME\": ").append(escapeJson(entry.className)).append(",\n");
                    sb.append("    \"METHOD_NAME\": ").append(escapeJson(entry.methodName)).append(",\n");
                    sb.append("    \"METHOD_SIGNATURE\": ").append(escapeJson(entry.methodSignature)).append(",\n");
                    sb.append("    \"OWNER_CLASS\": ").append(escapeJson(entry.ownerClass)).append(",\n");
                    sb.append("    \"FIELD_NAME\": ").append(escapeJson(entry.fieldName)).append(",\n");
                    sb.append("    \"PARAMETERS\": ").append(escapeJson(entry.parameters)).append(",\n");
                    sb.append("    \"STACK_TRACE\": ").append(escapeJson(entry.stackTrace)).append(",\n");
                    sb.append("    \"REFERENCE_INFO\": ").append(escapeJson(entry.referenceInfo)).append(",\n");
                    sb.append("    \"THREAD_NAME\": ").append(escapeJson(entry.threadName)).append(",\n");
                    sb.append("    \"THREAD_ID\": ").append(entry.threadId).append(",\n");
                    sb.append("    \"CREATED_AT\": ").append(escapeJson(createdAt)).append("\n");
                    sb.append("  }");

                    fallbackFileWriter.write(sb.toString());
                }

                fallbackFileWriter.flush();
            }

        } catch (Exception e) {
            System.err.println("批量写入 JSON 日志失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 简单的 JSON 字符串转义
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }


    /**
     * 获取统计信息
     */
    public String getStatistics() {
        String mode = "JSON文件";
        String path = dbPath + ".json";
        return String.format("总记录数: %d, 缓冲区大小: %d, 模式: %s, 路径: %s", 
                           totalRecords.get(), logBuffer.size(), mode, path);
    }
    
    /**
     * 关闭日志记录器
     */
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            System.out.println("🔄 正在关闭JSON日志记录器...");
            
            try {
                // 等待缓冲区数据写完
                writeExecutor.shutdown();
                if (!writeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    writeExecutor.shutdownNow();
                }
                
                // 关闭 JSON 日志文件（补上结尾的 "]"）
                synchronized (fallbackLock) {
                    if (fallbackFileWriter != null) {
                        try {
                            fallbackFileWriter.write("\n]\n");
                            fallbackFileWriter.close();
                            fallbackFileWriter = null;
                        } catch (Exception e) {
                            System.err.println("关闭降级文件日志时出错: " + e.getMessage());
                        }
                    }
                }
                
                System.out.println("✅ 日志记录器已关闭，" + getStatistics());
                
            } catch (Exception e) {
                System.err.println("关闭日志记录器时出错: " + e.getMessage());
            }
        }
    }
    
    // 单例模式
    private static volatile HighPerformanceSQLiteLogger instance;
    
    public static HighPerformanceSQLiteLogger getInstance() {
        if (instance == null) {
            synchronized (HighPerformanceSQLiteLogger.class) {
                if (instance == null) {
                    instance = new HighPerformanceSQLiteLogger();
                }
            }
        }
        return instance;
    }

    /**
     * 新增：带引用信息的Hook日志条目
     */
    public static class HookLogEntryWithReferences extends HookLogEntry {
        public final String referenceInfo;

        public HookLogEntryWithReferences(String className, String methodName, String methodSignature,
                                          String ownerClass, String fieldName, String parameters,
                                          String stackTrace, String referenceInfo) {
            super(className, methodName, methodSignature, ownerClass, fieldName, parameters, stackTrace);
            this.referenceInfo = referenceInfo;
        }
    }

    /**
     * 新增：记录带引用信息的hook日志（异步）
     * 这是对现有logHook方法的扩展，保持向后兼容性
     */
    public void logHookWithReferences(String className, String methodName, String methodSignature,
                                      String ownerClass, String fieldName, String parameters,
                                      String stackTrace, String referenceInfo) {

        if (isShutdown.get()) {
            return;
        }

        HookLogEntry entry = new HookLogEntry(
                className, methodName, methodSignature, ownerClass, fieldName,
                parameters, stackTrace, referenceInfo
        );

        // 尝试添加到缓冲区
        if (!logBuffer.offer(entry)) {
            System.err.println("日志缓冲区已满，丢弃日志记录: " + className + "." + methodName);
        }
    }

    /**
     * 新增：记录断点日志
     */
    public void logBreakpoint(String breakpointId, String className, String methodName, 
                             String breakpointType, String parameters, String stackTrace, String status) {
        // 当前版本默认只对方法 Hook 做 JSON 持久化，断点日志暂不写入文件
        // 如有需要，可以在此处扩展为 JSON 结构，与 Hook 日志共用同一个数组文件
        if (isShutdown.get()) {
            return;
        }
        // 简单打印到控制台，避免静默丢失
        System.out.println("📌 Breakpoint fired: id=" + breakpointId +
                ", class=" + className + ", method=" + methodName +
                ", type=" + breakpointType + ", status=" + status);
    }
}
