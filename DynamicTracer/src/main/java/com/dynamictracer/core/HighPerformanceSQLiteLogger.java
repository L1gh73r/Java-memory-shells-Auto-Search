package com.dynamictracer.core;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 高性能H2数据库日志记录器
 * 
 * 特点：
 * 1. 异步批量写入，避免阻塞hook执行
 * 2. 内存缓冲区，定期批量刷新到数据库
 * 3. 连接复用和优化配置
 * 4. Bootstrap加载器兼容（纯Java实现）
 * 5. 支持1.5万+hook点的高并发场景
 */
public class HighPerformanceSQLiteLogger {
    
    // 数据库文件路径
    private final String dbPath;
    
    // 数据库连接
    private volatile Connection connection;
    
    // 预编译插入语句
    private volatile PreparedStatement insertStatement;
    
    // 断点日志预编译插入语句
    private volatile PreparedStatement breakpointInsertStatement;
    
    // 异步写入线程池（单线程保证写入顺序）
    private final ExecutorService writeExecutor;
    
    // 内存缓冲区
    private final BlockingQueue<HookLogEntry> logBuffer;
    
    // 批量写入大小
    private static final int BATCH_SIZE = 1000;
    
    // 缓冲区最大大小
    private static final int BUFFER_SIZE = 10000;
    
    // 定期刷新间隔（毫秒）
    private static final long FLUSH_INTERVAL = 5000;
    
    // 控制标志
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicLong totalRecords = new AtomicLong(0);
    
    // 定期刷新任务
    private final ScheduledExecutorService flushScheduler;
    
    // 降级机制 - 文件日志
    private volatile java.io.FileWriter fallbackFileWriter;
    private final Object fallbackLock = new Object();
    private volatile boolean useFallbackLogging = false;
    
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
        // H2数据库基础路径（不包含扩展名，H2会自动添加.mv.db）
        this.dbPath = "/Users/yulate/data/staticAnalysis/memory-find/logdata/ctf_hook_logs_" + 
            new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        this.logBuffer = new LinkedBlockingQueue<>(BUFFER_SIZE);
        
        // 创建单线程写入器
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CTFAgent-H2-Writer");
            t.setDaemon(true);
            return t;
        });
        
        // 创建定期刷新调度器
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CTFAgent-H2-Flusher");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化数据库
        initializeDatabase();
        
        // 启动异步写入任务
        startAsyncWriter();
        
        // 启动定期刷新任务
        startPeriodicFlusher();
        
        // 注册shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        
        System.out.println("🚀 高性能H2数据库日志记录器已启动，数据库路径: " + dbPath + ".mv.db");
    }
    
    /**
     * 初始化数据库和表结构
     */
    private void initializeDatabase() {
        try {
            // 确保目录存在
            java.io.File dbFile = new java.io.File(dbPath + ".mv.db");
            java.io.File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (parentDir.mkdirs()) {
                    System.out.println("✅ 创建数据库目录: " + parentDir.getAbsolutePath());
                } else {
                    System.err.println("❌ 无法创建数据库目录: " + parentDir.getAbsolutePath());
                }
            }
            
            // 加载H2 JDBC驱动 - 支持多种加载策略
            boolean driverLoaded = false;
            Exception lastException = null;
            
            // 策略1：使用当前类加载器
            try {
                Class.forName("org.h2.Driver");
                driverLoaded = true;
                System.out.println("✅ H2 JDBC驱动加载成功（当前类加载器）");
            } catch (Exception e) {
                lastException = e;
                System.out.println("⚠️  策略1失败: " + e.getMessage());
            }
            
            // 策略2：使用线程上下文类加载器
            if (!driverLoaded) {
                try {
                    ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
                    if (contextLoader != null) {
                        Class.forName("org.h2.Driver", true, contextLoader);
                        driverLoaded = true;
                        System.out.println("✅ H2 JDBC驱动加载成功（线程上下文类加载器）");
                    }
                } catch (Exception e) {
                    lastException = e;
                    System.out.println("⚠️  策略2失败: " + e.getMessage());
                }
            }
            
            // 策略3：使用系统类加载器
            if (!driverLoaded) {
                try {
                    ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
                    Class.forName("org.h2.Driver", true, systemLoader);
                    driverLoaded = true;
                    System.out.println("✅ H2 JDBC驱动加载成功（系统类加载器）");
                } catch (Exception e) {
                    lastException = e;
                    System.out.println("⚠️  策略3失败: " + e.getMessage());
                }
            }
            
            // 策略4：直接尝试连接（让DriverManager自动发现）
            if (!driverLoaded) {
                System.out.println("🔄 尝试直接连接数据库...");
                // 继续执行，让DriverManager尝试自动发现驱动
            }
            
            if (!driverLoaded && lastException != null) {
                System.err.println("⚠️  所有H2驱动加载策略都失败，将尝试直接连接");
            }
            
            // 创建数据库连接
            // H2文件数据库URL格式（H2会自动添加.mv.db扩展名）
            String url = "jdbc:h2:file:" + dbPath + ";MODE=MySQL;AUTO_SERVER=TRUE";
            System.out.println("🔗 连接数据库: " + url);
            System.out.println("📁 数据库文件将创建为: " + dbPath + ".mv.db");
            connection = DriverManager.getConnection(url);
            
            if (connection == null) {
                throw new SQLException("数据库连接创建失败");
            }
            
            System.out.println("✅ 数据库连接创建成功");
            
            // 优化H2数据库配置
            try (Statement stmt = connection.createStatement()) {
                // H2优化配置
                stmt.execute("SET CACHE_SIZE 10000"); // 设置缓存大小
                stmt.execute("SET LOG 0"); // 禁用事务日志以提高性能（嵌入式模式）
                stmt.execute("SET UNDO_LOG 0"); // 禁用undo日志
                stmt.execute("SET REFERENTIAL_INTEGRITY FALSE"); // 禁用外键约束检查
                
                System.out.println("✅ H2数据库优化配置已应用");
            }
            
            // 创建表结构
            createTables();
            
            // 准备插入语句
            prepareStatements();
            
            // 验证数据库文件是否真的创建了
            java.io.File actualDbFile = new java.io.File(dbPath + ".mv.db");
            if (actualDbFile.exists()) {
                System.out.println("✅ 数据库文件创建成功: " + actualDbFile.getAbsolutePath() + " (大小: " + actualDbFile.length() + " 字节)");
            } else {
                System.err.println("⚠️  数据库文件似乎没有创建: " + actualDbFile.getAbsolutePath());
            }
            
        } catch (SQLException e) {
            System.err.println("❌ 初始化数据库失败: " + e.getMessage());
            e.printStackTrace();
            // 设置connection为null，确保后续操作知道数据库不可用
            connection = null;
            insertStatement = null;
            // 启用降级文件日志
            initializeFallbackLogging();
        } catch (Exception e) {
            System.err.println("❌ 初始化数据库时发生意外错误: " + e.getMessage());
            e.printStackTrace();
            connection = null;
            insertStatement = null;
            // 启用降级文件日志
            initializeFallbackLogging();
        }
    }
    
    /**
     * 创建表结构
     */
    private void createTables() throws SQLException {
        String createTableSQL = 
            "CREATE TABLE IF NOT EXISTS hook_logs (" +
            "    id INTEGER PRIMARY KEY AUTO_INCREMENT," +
            "    timestamp BIGINT NOT NULL," +
            "    date_time VARCHAR(50) NOT NULL," +
            "    class_name VARCHAR(500) NOT NULL," +
            "    method_name VARCHAR(200) NOT NULL," +
            "    method_signature VARCHAR(500)," +
            "    owner_class VARCHAR(500)," +
            "    field_name VARCHAR(200)," +
            "    parameters TEXT," +
            "    stack_trace TEXT," +
            "    reference_info TEXT," +
            "    thread_name VARCHAR(100)," +
            "    thread_id BIGINT," +
            "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            ")";
            
        // 新增：断点日志表
        String createBreakpointTableSQL = 
            "CREATE TABLE IF NOT EXISTS breakpoint_logs (" +
            "    id INTEGER PRIMARY KEY AUTO_INCREMENT," +
            "    timestamp BIGINT NOT NULL," +
            "    date_time VARCHAR(50) NOT NULL," +
            "    breakpoint_id VARCHAR(100) NOT NULL," +
            "    class_name VARCHAR(500) NOT NULL," +
            "    method_name VARCHAR(200) NOT NULL," +
            "    breakpoint_type VARCHAR(20) NOT NULL," +  // BEFORE, AFTER
            "    parameters TEXT," +
            "    stack_trace TEXT," +
            "    status VARCHAR(20) NOT NULL," +  // TRIGGERED, EXECUTED, ERROR
            "    thread_name VARCHAR(100)," +
            "    thread_id BIGINT," +
            "    execution_time_ms BIGINT," +  // 断点执行耗时
            "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            ")";
            
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
            stmt.execute(createBreakpointTableSQL);
            
            // 创建hook_logs表的索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON hook_logs(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_class_method ON hook_logs(class_name, method_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_owner_field ON hook_logs(owner_class, field_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_thread_id ON hook_logs(thread_id)");
            
            // 创建breakpoint_logs表的索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bp_timestamp ON breakpoint_logs(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bp_id ON breakpoint_logs(breakpoint_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bp_class_method ON breakpoint_logs(class_name, method_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bp_type ON breakpoint_logs(breakpoint_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bp_status ON breakpoint_logs(status)");
            
            System.out.println("✅ 数据库表和索引创建完成（包含断点日志表）");
        }
    }
    
    /**
     * 准备预编译语句
     */
    private void prepareStatements() throws SQLException {
        String insertSQL = 
            "INSERT INTO hook_logs (" +
            "    timestamp, date_time, class_name, method_name, method_signature," +
            "    owner_class, field_name, parameters, stack_trace, reference_info, thread_name, thread_id" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            
        String breakpointInsertSQL = 
            "INSERT INTO breakpoint_logs (" +
            "    timestamp, date_time, breakpoint_id, class_name, method_name, breakpoint_type," +
            "    parameters, stack_trace, status, thread_name, thread_id, execution_time_ms" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            
        insertStatement = connection.prepareStatement(insertSQL);
        breakpointInsertStatement = connection.prepareStatement(breakpointInsertSQL);
        System.out.println("✅ 预编译语句准备完成（包含断点日志）");
    }
    
    /**
     * 初始化降级文件日志
     */
    private void initializeFallbackLogging() {
        try {
            String fallbackPath = dbPath + "_fallback.log";
            synchronized (fallbackLock) {
                if (fallbackFileWriter == null) {
                    // 确保目录存在
                    java.io.File logFile = new java.io.File(fallbackPath);
                    java.io.File parentDir = logFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    
                    fallbackFileWriter = new java.io.FileWriter(fallbackPath, true);
                    useFallbackLogging = true;
                    System.out.println("🔄 启用降级文件日志: " + fallbackPath);
                    
                    // 写入降级日志的头部信息
                    fallbackFileWriter.write("=== CTFAgent 降级日志开始 ===\n");
                    fallbackFileWriter.write("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n");
                    fallbackFileWriter.write("原因: SQLite数据库初始化失败\n");
                    fallbackFileWriter.write("================================\n\n");
                    fallbackFileWriter.flush();
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 初始化降级文件日志失败: " + e.getMessage());
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
        
        // 检查数据库是否可用
        if (connection == null || insertStatement == null) {
            // 使用降级文件日志
            if (useFallbackLogging && fallbackFileWriter != null) {
                logToFallbackFile(className, methodName, methodSignature, ownerClass, fieldName, parameters, stackTrace);
            } else {
                // 仅在前几次失败时输出警告，避免日志刷屏
                long failCount = totalRecords.get();
                if (failCount < 5) {
                    System.err.println("⚠️  数据库和降级日志都不可用: " + className + "." + methodName);
                }
            }
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
     * 写入降级文件日志
     */
    private void logToFallbackFile(String className, String methodName, String methodSignature,
                                  String ownerClass, String fieldName, String parameters, String stackTrace) {
        try {
            synchronized (fallbackLock) {
                if (fallbackFileWriter != null) {
                    String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
                    
                    fallbackFileWriter.write("=== Hook记录 ===\n");
                    fallbackFileWriter.write("时间: " + timestamp + "\n");
                    fallbackFileWriter.write("类名: " + className + "\n");
                    fallbackFileWriter.write("方法名: " + methodName + "\n");
                    fallbackFileWriter.write("方法签名: " + methodSignature + "\n");
                    fallbackFileWriter.write("所有者类: " + ownerClass + "\n");
                    fallbackFileWriter.write("字段名: " + fieldName + "\n");
                    fallbackFileWriter.write("参数: " + parameters + "\n");
                    fallbackFileWriter.write("堆栈: " + stackTrace + "\n");
                    fallbackFileWriter.write("线程: " + Thread.currentThread().getName() + "\n");
                    fallbackFileWriter.write("================\n\n");
                    fallbackFileWriter.flush();
                    
                    // 更新记录计数
                    long count = totalRecords.incrementAndGet();
                    if (count <= 5) {
                        System.out.println("📝 降级日志记录: " + className + "." + methodName + " (总计: " + count + ")");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 写入降级日志失败: " + e.getMessage());
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
     * 启动定期刷新任务
     */
    private void startPeriodicFlusher() {
        flushScheduler.scheduleAtFixedRate(() -> {
            try {
                if (connection != null && !connection.isClosed()) {
                    // 刷新预编译语句
                    if (insertStatement != null) {
                        insertStatement.executeBatch();
                        connection.commit();
                    }
                }
            } catch (SQLException e) {
                System.err.println("定期刷新出错: " + e.getMessage());
            }
        }, FLUSH_INTERVAL, FLUSH_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * 修改：批量写入数据库 - 支持引用信息
     * 这个方法需要小幅修改以支持新的日志条目类型
     */
    private void writeBatch(List<HookLogEntry> entries) {
        if (entries.isEmpty() || connection == null || insertStatement == null) {
            return;
        }

        try {
            connection.setAutoCommit(false);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

            for (HookLogEntry entry : entries) {
                insertStatement.setLong(1, entry.timestamp);
                insertStatement.setString(2, sdf.format(new Date(entry.timestamp)));
                insertStatement.setString(3, entry.className);
                insertStatement.setString(4, entry.methodName);
                insertStatement.setString(5, entry.methodSignature);
                insertStatement.setString(6, entry.ownerClass);
                insertStatement.setString(7, entry.fieldName);
                insertStatement.setString(8, entry.parameters);
                insertStatement.setString(9, entry.stackTrace);
                insertStatement.setString(10, entry.referenceInfo); // 新增：引用信息单独一列
                insertStatement.setString(11, entry.threadName);
                insertStatement.setLong(12, entry.threadId);

                insertStatement.addBatch();
            }

            insertStatement.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);

            totalRecords.addAndGet(entries.size());

        } catch (SQLException e) {
            System.err.println("批量写入失败: " + e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                System.err.println("回滚失败: " + rollbackEx.getMessage());
            }
        }
    }


    /**
     * 获取统计信息
     */
    public String getStatistics() {
        String mode = useFallbackLogging ? "降级文件日志" : "H2数据库";
        String path = useFallbackLogging ? (dbPath + "_fallback.log") : (dbPath + ".mv.db");
        return String.format("总记录数: %d, 缓冲区大小: %d, 模式: %s, 路径: %s", 
                           totalRecords.get(), logBuffer.size(), mode, path);
    }
    
    /**
     * 关闭日志记录器
     */
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            System.out.println("🔄 正在关闭H2日志记录器...");
            
            // 停止接收新数据
            flushScheduler.shutdown();
            
            try {
                // 等待缓冲区数据写完
                writeExecutor.shutdown();
                if (!writeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    writeExecutor.shutdownNow();
                }
                
                // 关闭数据库连接
                if (insertStatement != null) {
                    insertStatement.close();
                }
                if (breakpointInsertStatement != null) {
                    breakpointInsertStatement.close();
                }
                if (connection != null) {
                    connection.close();
                }
                
                // 关闭降级文件日志
                synchronized (fallbackLock) {
                    if (fallbackFileWriter != null) {
                        try {
                            fallbackFileWriter.write("=== CTFAgent 降级日志结束 ===\n");
                            fallbackFileWriter.write("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n\n");
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

        // 检查数据库是否可用
        if (connection == null || insertStatement == null) {
            // 使用降级文件日志
            if (useFallbackLogging && fallbackFileWriter != null) {
                logToFallbackFileWithReferences(className, methodName, methodSignature,
                        ownerClass, fieldName, parameters, stackTrace, referenceInfo);
            }
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
     * 新增：降级文件日志支持引用信息
     */
    private void logToFallbackFileWithReferences(String className, String methodName, String methodSignature,
                                                 String ownerClass, String fieldName, String parameters,
                                                 String stackTrace, String referenceInfo) {
        try {
            synchronized (fallbackLock) {
                if (fallbackFileWriter != null) {
                    String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());

                    fallbackFileWriter.write("=== Hook记录（含引用追踪） ===\n");
                    fallbackFileWriter.write("时间: " + timestamp + "\n");
                    fallbackFileWriter.write("类名: " + className + "\n");
                    fallbackFileWriter.write("方法名: " + methodName + "\n");
                    fallbackFileWriter.write("方法签名: " + methodSignature + "\n");
                    fallbackFileWriter.write("所有者类: " + ownerClass + "\n");
                    fallbackFileWriter.write("字段名: " + fieldName + "\n");
                    fallbackFileWriter.write("参数: " + parameters + "\n");
                    fallbackFileWriter.write("堆栈: " + stackTrace + "\n");
                    fallbackFileWriter.write("引用信息: " + referenceInfo + "\n");
                    fallbackFileWriter.write("线程: " + Thread.currentThread().getName() + "\n");
                    fallbackFileWriter.write("================\n\n");
                    fallbackFileWriter.flush();

                    long count = totalRecords.incrementAndGet();
                    if (count <= 5) {
                        System.out.println("降级日志记录（含引用）: " + className + "." + methodName + " (总计: " + count + ")");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("写入降级日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 新增：记录断点日志
     */
    public void logBreakpoint(String breakpointId, String className, String methodName, 
                             String breakpointType, String parameters, String stackTrace, String status) {
        if (isShutdown.get()) {
            return;
        }
        
        try {
            long timestamp = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            String dateTime = sdf.format(new Date(timestamp));
            
            Thread currentThread = Thread.currentThread();
            String threadName = currentThread.getName();
            long threadId = currentThread.getId();
            
            // 如果数据库可用，直接写入
            if (connection != null && breakpointInsertStatement != null) {
                synchronized (breakpointInsertStatement) {
                    breakpointInsertStatement.setLong(1, timestamp);
                    breakpointInsertStatement.setString(2, dateTime);
                    breakpointInsertStatement.setString(3, breakpointId);
                    breakpointInsertStatement.setString(4, className);
                    breakpointInsertStatement.setString(5, methodName);
                    breakpointInsertStatement.setString(6, breakpointType);
                    breakpointInsertStatement.setString(7, parameters);
                    breakpointInsertStatement.setString(8, stackTrace);
                    breakpointInsertStatement.setString(9, status);
                    breakpointInsertStatement.setString(10, threadName);
                    breakpointInsertStatement.setLong(11, threadId);
                    breakpointInsertStatement.setLong(12, 0); // execution_time_ms，暂时设为0
                    
                    breakpointInsertStatement.executeUpdate();
                }
            } else {
                // 降级到文件日志
                logBreakpointToFallbackFile(breakpointId, className, methodName, breakpointType, 
                                           parameters, stackTrace, status, dateTime, threadName, threadId);
            }
            
        } catch (Exception e) {
            System.err.println("记录断点日志失败: " + e.getMessage());
            // 尝试降级到文件日志
            try {
                logBreakpointToFallbackFile(breakpointId, className, methodName, breakpointType, 
                                           parameters, stackTrace, status, 
                                           new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()),
                                           Thread.currentThread().getName(), Thread.currentThread().getId());
            } catch (Exception fallbackEx) {
                System.err.println("降级断点日志记录也失败: " + fallbackEx.getMessage());
            }
        }
    }
    
    /**
     * 断点日志降级到文件
     */
    private void logBreakpointToFallbackFile(String breakpointId, String className, String methodName,
                                           String breakpointType, String parameters, String stackTrace,
                                           String status, String dateTime, String threadName, long threadId) {
        try {
            synchronized (fallbackLock) {
                if (fallbackFileWriter != null) {
                    fallbackFileWriter.write("=== 断点日志记录 ===\n");
                    fallbackFileWriter.write("时间: " + dateTime + "\n");
                    fallbackFileWriter.write("断点ID: " + breakpointId + "\n");
                    fallbackFileWriter.write("类名: " + className + "\n");
                    fallbackFileWriter.write("方法名: " + methodName + "\n");
                    fallbackFileWriter.write("断点类型: " + breakpointType + "\n");
                    fallbackFileWriter.write("参数: " + parameters + "\n");
                    fallbackFileWriter.write("堆栈: " + stackTrace + "\n");
                    fallbackFileWriter.write("状态: " + status + "\n");
                    fallbackFileWriter.write("线程: " + threadName + " (ID: " + threadId + ")\n");
                    fallbackFileWriter.write("==================\n\n");
                    fallbackFileWriter.flush();
                }
            }
        } catch (Exception e) {
            System.err.println("写入断点降级日志失败: " + e.getMessage());
        }
    }
}
