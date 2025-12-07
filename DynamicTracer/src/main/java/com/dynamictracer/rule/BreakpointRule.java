package com.dynamictracer.rule;

import com.dynamictracer.core.ClassModifier;
import com.dynamictracer.core.HighPerformanceSQLiteLogger;
import javassist.*;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 断点规则 - 支持在指定方法处设置断点并执行自定义代码
 *
 * 功能特性：
 * 1. 通过JSON配置文件灵活配置断点
 * 2. 支持方法执行前后断点
 * 3. 支持条件断点（参数匹配、自定义表达式）
 * 4. 支持自定义代码执行
 * 5. 支持暂停程序执行
 * 6. 使用独立的数据库表记录断点日志
 */
public class BreakpointRule implements ClassModifier {

    // 断点配置缓存
    private final Map<String, List<BreakpointConfig>> classBreakpoints = new ConcurrentHashMap<>();

    // 代码模板缓存 (预留给未来的JSON配置加载功能)
    @SuppressWarnings("unused")
    private final Map<String, CodeTemplate> codeTemplates = new ConcurrentHashMap<>();

    // 断点统计
    private static final AtomicLong breakpointTriggerCount = new AtomicLong(0);

    // 配置文件路径
    private final String configFilePath;

    // 日志记录器 (暂时保留，未来可能用于更复杂的日志功能)
    @SuppressWarnings("unused")
    private final HighPerformanceSQLiteLogger logger;

    // 防重入检查
    private static final ThreadLocal<Boolean> inBreakpoint = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    // 黑名单 - 不应该被断点的类
    private static final Set<String> BLACKLISTED_CLASSES = new HashSet<>(Arrays.asList(
        "java.", "sun.", "javax.", "com.sun.", "javassist.", "com.dynamictracer."
    ));

    /**
     * 断点配置模型
     */
    public static class BreakpointConfig {
        private String id;
        private boolean enabled;
        private String targetClass;
        private String targetMethod;
        private String methodSignature;
        private String breakpointType; // BEFORE, AFTER
        private String conditionType;  // ALWAYS, PARAMETER_MATCH, CUSTOM_EXPRESSION
        private String conditionExpression;
        private String customCode;
        private String codeTemplate;  // 代码模板名称
        private Map<String, String> codeParams; // 代码模板参数
        private String codeFile;      // 外部代码文件路径
        private boolean pauseExecution;
        private long pauseDuration;
        private boolean logToDatabase;

        // 构造函数
        public BreakpointConfig() {}

        public BreakpointConfig(String id, boolean enabled, String targetClass, String targetMethod,
                               String breakpointType, String conditionType, String customCode,
                               boolean pauseExecution, long pauseDuration) {
            this.id = id;
            this.enabled = enabled;
            this.targetClass = targetClass;
            this.targetMethod = targetMethod;
            this.breakpointType = breakpointType;
            this.conditionType = conditionType;
            this.customCode = customCode;
            this.pauseExecution = pauseExecution;
            this.pauseDuration = pauseDuration;
            this.logToDatabase = true;
        }

        // Getter和Setter方法
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getTargetClass() { return targetClass; }
        public void setTargetClass(String targetClass) { this.targetClass = targetClass; }

        public String getTargetMethod() { return targetMethod; }
        public void setTargetMethod(String targetMethod) { this.targetMethod = targetMethod; }

        public String getMethodSignature() { return methodSignature; }
        public void setMethodSignature(String methodSignature) { this.methodSignature = methodSignature; }

        public String getBreakpointType() { return breakpointType; }
        public void setBreakpointType(String breakpointType) { this.breakpointType = breakpointType; }

        public String getConditionType() { return conditionType; }
        public void setConditionType(String conditionType) { this.conditionType = conditionType; }

        public String getConditionExpression() { return conditionExpression; }
        public void setConditionExpression(String conditionExpression) { this.conditionExpression = conditionExpression; }

        public String getCustomCode() { return customCode; }
        public void setCustomCode(String customCode) { this.customCode = customCode; }

        public boolean isPauseExecution() { return pauseExecution; }
        public void setPauseExecution(boolean pauseExecution) { this.pauseExecution = pauseExecution; }

        public long getPauseDuration() { return pauseDuration; }
        public void setPauseDuration(long pauseDuration) { this.pauseDuration = pauseDuration; }

        public boolean isLogToDatabase() { return logToDatabase; }
        public void setLogToDatabase(boolean logToDatabase) { this.logToDatabase = logToDatabase; }

        public String getCodeTemplate() { return codeTemplate; }
        public void setCodeTemplate(String codeTemplate) { this.codeTemplate = codeTemplate; }

        public Map<String, String> getCodeParams() { return codeParams; }
        public void setCodeParams(Map<String, String> codeParams) { this.codeParams = codeParams; }

        public String getCodeFile() { return codeFile; }
        public void setCodeFile(String codeFile) { this.codeFile = codeFile; }
    }

    /**
     * 代码模板类
     */
    public static class CodeTemplate {
        private String description;
        private List<String> imports;
        private List<String> code;

        public CodeTemplate() {}

        public CodeTemplate(String description, List<String> imports, List<String> code) {
            this.description = description;
            this.imports = imports;
            this.code = code;
        }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public List<String> getImports() { return imports; }
        public void setImports(List<String> imports) { this.imports = imports; }

        public List<String> getCode() { return code; }
        public void setCode(List<String> code) { this.code = code; }

        /**
         * 生成最终的Java代码
         */
        public String generateCode(Map<String, String> params, String className, String methodName) {
            if (code == null || code.isEmpty()) {
                return "";
            }

            StringBuilder result = new StringBuilder();
            for (String line : code) {
                String processedLine = line;

                // 替换模板参数
                if (params != null) {
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        String placeholder = "{{" + entry.getKey() + "}}";
                        processedLine = processedLine.replace(placeholder, entry.getValue());
                    }
                }

                // 替换内置变量
                processedLine = processedLine.replace("{{className}}", className);
                processedLine = processedLine.replace("{{methodName}}", methodName);

                result.append(processedLine).append("\n");
            }

            return result.toString();
        }
    }


    /**
     * 构造函数
     */
    public BreakpointRule() {
        // 获取配置文件路径
        this.configFilePath = System.getProperty("dynamictracer.breakpoint.config", "breakpoint-config.json");
        this.logger = HighPerformanceSQLiteLogger.getInstance();


        // 加载断点配置
        loadBreakpointConfig();

        System.out.println("断点规则已初始化，配置文件: " + configFilePath);
        System.out.println("已加载 " + getTotalBreakpointCount() + " 个断点配置");
    }

    /**
     * 加载断点配置
     */
    private void loadBreakpointConfig() {
        try {
            // 尝试从JSON文件加载配置
            boolean jsonLoaded = loadBreakpointConfigFromJson();

            if (!jsonLoaded) {
                System.out.println("JSON配置文件未找到，使用示例配置");
                createSampleBreakpoints();
            }

            System.out.println("断点配置加载完成");

        } catch (Exception e) {
            System.err.println("加载断点配置失败: " + e.getMessage());
            e.printStackTrace();

            // 降级到示例配置
            System.out.println("降级到示例配置");
            createSampleBreakpoints();
        }
    }

    /**
     * 从JSON文件加载断点配置
     */
    private boolean loadBreakpointConfigFromJson() {
        try {
            java.io.File configFile = new java.io.File("src/main/resources/rules/" + configFilePath);
            if (!configFile.exists()) {
                // 尝试相对路径
                configFile = new java.io.File(configFilePath);
                if (!configFile.exists()) {
                    System.out.println("JSON配置文件未找到: " + configFilePath);
                    return false;
                }
            }

            System.out.println("读取JSON配置文件: " + configFile.getAbsolutePath());
            // 读取文件内容
            String content;
            java.util.Scanner scanner = new java.util.Scanner(configFile, "UTF-8");
            try {
                scanner.useDelimiter("\\Z");
                content = scanner.hasNext() ? scanner.next() : "";
            } finally {
                scanner.close();
            }

            // 支持两种最简格式：
            // 1) 根对象包含 targetClass / targetMethod / id 可选
            // 2) breakpoints[0] 对象包含 targetClass / targetMethod / id 可选
            String targetClass = extractJsonString(content, "targetClass");
            String targetMethod = extractJsonString(content, "targetMethod");
            String id = extractJsonString(content, "id");
            if (targetClass == null || targetMethod == null) {
                // 尝试在 breakpoints[0] 中读取
                targetClass = extractJsonStringInArrayObject(content, "breakpoints", 0, "targetClass");
                targetMethod = extractJsonStringInArrayObject(content, "breakpoints", 0, "targetMethod");
                id = id == null ? extractJsonStringInArrayObject(content, "breakpoints", 0, "id") : id;
            }

            if (targetClass == null || targetMethod == null) {
                System.out.println("未在配置中找到 targetClass/targetMethod，将使用示例配置");
                return false;
            }

            if (id == null || id.trim().isEmpty()) {
                id = "bp_" + Math.abs(targetClass.hashCode()) + "_" + targetMethod;
            }

            BreakpointConfig jsonConfig = new BreakpointConfig(
                id, true, targetClass, targetMethod, "BEFORE", "ALWAYS", null, false, 0
            );
            // 默认启用 JOSEARCHER_SEARCH 模板
            jsonConfig.setCodeTemplate("JOSEARCHER_SEARCH");
            Map<String, String> params = new HashMap<>();
            // 可选参数，给出安全默认值
            params.put("searchType", "Request");
            params.put("blacklistType", "java.io.File");
            params.put("maxDepth", "20");
            jsonConfig.setCodeParams(params);

            addBreakpointConfig(targetClass, jsonConfig);

            System.out.println("JSON配置加载成功，断点ID: " + jsonConfig.getId());
            return true;

        } catch (Exception e) {
            System.err.println("JSON配置加载失败: " + e.getMessage());
            return false;
        }
    }

    // 最简JSON取值（根级键）
    private static String extractJsonString(String content, String key) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
            java.util.regex.Matcher m = p.matcher(content);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignore) {}
        return null;
    }

    // 从数组的第 index 个对象中取 key
    private static String extractJsonStringInArrayObject(String content, String arrayKey, int index, String key) {
        try {
            java.util.regex.Pattern arrayPattern = java.util.regex.Pattern.compile("\\\"" + java.util.regex.Pattern.quote(arrayKey) + "\\\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher arrayMatcher = arrayPattern.matcher(content);
            if (arrayMatcher.find()) {
                String arrayBody = arrayMatcher.group(1);
                // 取第 index 个对象
                java.util.regex.Pattern objPattern = java.util.regex.Pattern.compile("\\{(.*?)\\}", java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher objMatcher = objPattern.matcher(arrayBody);
                int i = 0;
                while (objMatcher.find()) {
                    if (i == index) {
                        String obj = objMatcher.group(1);
                        return extractJsonString(obj, key);
                    }
                    i++;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * 创建示例断点配置
     */
    private void createSampleBreakpoints() {
        // 示例1: 使用代码模板的断点
        BreakpointConfig templateBreakpoint = new BreakpointConfig(
            "template_example", true, "org.springframework.context.event.SimpleApplicationEventMulticaster",
            "multicastEvent", "BEFORE", "ALWAYS", null, true, 1000
        );
        templateBreakpoint.setCodeTemplate("JOSEARCHER_SEARCH");
        Map<String, String> params = new HashMap<>();
        params.put("searchType", "Request");
        params.put("blacklistType", "java.io.File");
        params.put("maxDepth", "20");
        params.put("reportPath", "/tmp");
        templateBreakpoint.setCodeParams(params);
        addBreakpointConfig("org.springframework.context.event.SimpleApplicationEventMulticaster", templateBreakpoint);

        // 示例2: 简单的自定义代码
        BreakpointConfig simpleBreakpoint = new BreakpointConfig(
            "simple_log", false, ".*Service.*", ".*", "BEFORE", "ALWAYS",
            "System.out.println(\"Service方法调用监控\");", false, 0
        );
        addBreakpointConfig(".*Service.*", simpleBreakpoint);

        // 示例3: 内存监控模板
        BreakpointConfig memoryBreakpoint = new BreakpointConfig(
            "memory_monitor", false, ".*", "save.*", "BEFORE", "ALWAYS", null, false, 0
        );
        memoryBreakpoint.setCodeTemplate("MEMORY_MONITOR");
        addBreakpointConfig(".*", memoryBreakpoint);
    }

    /**
     * 添加断点配置
     */
    private void addBreakpointConfig(String classPattern, BreakpointConfig config) {
        classBreakpoints.computeIfAbsent(classPattern, k -> new ArrayList<>()).add(config);
    }

    /**
     * 获取总断点数量
     */
    private int getTotalBreakpointCount() {
        return classBreakpoints.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public byte[] modify(ClassLoader loader, String className, Class<?> classBeingRedefined, byte[] classfileBuffer) {
        // 检查黑名单
        if (isBlacklisted(className)) {
            return classfileBuffer;
        }

        // 检查是否有匹配的断点配置
        List<BreakpointConfig> matchingBreakpoints = findMatchingBreakpoints(className);
        if (matchingBreakpoints.isEmpty()) {
            // // 调试：输出正在检查的类名
            // if (className.contains("SimpleApplicationEventMulticaster") || className.contains("Application")) {
            //     System.out.println("检查类但无匹配断点: " + className);
            // }
            return classfileBuffer;
        }

        // System.out.println("找到匹配的断点配置，类: " + className + ", 断点数: " + matchingBreakpoints.size());

        try {
            ClassPool pool = ClassPool.getDefault();
            pool.appendClassPath(new javassist.LoaderClassPath(loader));
            pool.appendClassPath(new javassist.ClassClassPath(BreakpointRule.class));

            CtClass cc = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
            boolean modified = false;

            // 遍历所有方法，查找需要添加断点的方法
            CtMethod[] methods = cc.getDeclaredMethods();
            for (CtMethod method : methods) {
                for (BreakpointConfig config : matchingBreakpoints) {
                    if (shouldAddBreakpoint(method, config)) {
                        if (addBreakpointToMethod(method, config)) {
                            modified = true;
                            System.out.println("已添加断点 [" + config.getId() + "] 到 " +
                                             className + "." + method.getName());
                        }
                    }
                }
            }

            if (modified) {
                return cc.toBytecode();
            }

        } catch (Exception e) {
            System.err.println("修改类 " + className + " 时出错: " + e.getMessage());
            e.printStackTrace();
        }

        return classfileBuffer;
    }

    /**
     * 查找匹配的断点配置
     */
    private List<BreakpointConfig> findMatchingBreakpoints(String className) {
        List<BreakpointConfig> matching = new ArrayList<>();

        for (Map.Entry<String, List<BreakpointConfig>> entry : classBreakpoints.entrySet()) {
            String classPattern = entry.getKey();
            if (className.matches(classPattern)) {
                for (BreakpointConfig config : entry.getValue()) {
                    if (config.isEnabled() && className.matches(config.getTargetClass())) {
                        matching.add(config);
                    }
                }
            }
        }

        return matching;
    }

    /**
     * 判断是否应该为方法添加断点
     */
    private boolean shouldAddBreakpoint(CtMethod method, BreakpointConfig config) {
        try {
            String methodName = method.getName();

            // 检查方法名是否匹配
            if (!methodName.matches(config.getTargetMethod())) {
                return false;
            }

            // 跳过构造函数和静态初始化块
            if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) {
                return false;
            }

            // 跳过native、abstract、final方法
            int modifiers = method.getModifiers();
            if (Modifier.isNative(modifiers) || Modifier.isAbstract(modifiers) || Modifier.isFinal(modifiers)) {
                return false;
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 为方法添加断点
     */
    private boolean addBreakpointToMethod(CtMethod method, BreakpointConfig config) {
        try {
            String className = method.getDeclaringClass().getName();
            String methodName = method.getName();
            String breakpointId = config.getId();

            // 根据断点类型添加不同的代码
            if ("BEFORE".equals(config.getBreakpointType())) {
                // 在方法开始处添加断点
                String beforeCode = generateBreakpointCode(breakpointId, className, methodName, "BEFORE");
                method.insertBefore(beforeCode);
            } else if ("AFTER".equals(config.getBreakpointType())) {
                // 在方法结束处添加断点
                String afterCode = generateBreakpointCode(breakpointId, className, methodName, "AFTER");
                method.insertAfter(afterCode);
            }

            return true;

        } catch (Exception e) {
            System.err.println("为方法添加断点时出错: " + e.getMessage());
            return false;
        }
    }

    /**
     * 生成断点代码 - 直接插入用户配置的自定义代码
     */
    private String generateBreakpointCode(String breakpointId, String className, String methodName, String breakpointType) {
        // 查找对应的断点配置
        BreakpointConfig config = findBreakpointConfigById(breakpointId);
        if (config == null) {
            return generateDefaultBreakpointCode(breakpointId, className, methodName);
        }

        StringBuilder code = new StringBuilder();
        code.append("{\n");
        code.append("  try {\n");

        // 生成实际的自定义代码
        String customCode = generateActualCustomCode(config, className, methodName);
        if (customCode != null && !customCode.trim().isEmpty()) {
            code.append("    // 断点 [").append(breakpointId).append("] 自定义代码开始\n");
            code.append("    ").append(customCode).append("\n");
            code.append("    // 断点 [").append(breakpointId).append("] 自定义代码结束\n");
        }

        // 添加暂停逻辑
        if (config.isPauseExecution() && config.getPauseDuration() > 0) {
            code.append("    // 断点暂停执行\n");
            code.append("    Thread.sleep(").append(config.getPauseDuration()).append("L);\n");
        }

        code.append("  } catch (Exception e) {\n");
        code.append("    // 静默处理断点异常，不影响原方法执行\n");
        code.append("    System.err.println(\"断点执行出错 [").append(breakpointId).append("]: \" + e.getMessage());\n");
        code.append("  }\n");
        code.append("}\n");

        return code.toString();
    }

    /**
     * 生成实际的自定义代码
     */
    private String generateActualCustomCode(BreakpointConfig config, String className, String methodName) {
        // 优先级：customCode > codeTemplate > codeFile
        if (config.getCustomCode() != null && !config.getCustomCode().trim().isEmpty()) {
            return config.getCustomCode();
        } else if (config.getCodeTemplate() != null && !config.getCodeTemplate().trim().isEmpty()) {
            return generateCodeFromTemplate(config.getCodeTemplate(), config.getCodeParams(), className, methodName, config.getId());
        } else if (config.getCodeFile() != null && !config.getCodeFile().trim().isEmpty()) {
            return loadCodeFromFile(config.getCodeFile());
        }
        return null;
    }

    /**
     * 从模板生成代码（用于直接插入）
     */
    private String generateCodeFromTemplate(String templateName, Map<String, String> params, String className, String methodName, String breakpointId) {
        if ("SIMPLE_LOG".equals(templateName)) {
            String message = params != null ? params.getOrDefault("message", "断点触发") : "断点触发";
            return "System.out.println(\"" + message + " - 类: " + className + ", 方法: " + methodName + "\");";
        } else if ("MEMORY_MONITOR".equals(templateName)) {
            return "Runtime runtime = Runtime.getRuntime(); " +
                   "long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024; " +
                   "System.out.println(\"内存使用: \" + usedMemory + \"MB\");";
        } else if ("JOSEARCHER_SEARCH".equals(templateName)) {
            // 生成josearcher搜索代码（直接调用内联vendor类）
            String searchType = params != null ? params.getOrDefault("searchType", "Request") : "Request";
            String blacklistType = params != null ? params.getOrDefault("blacklistType", "java.io.File") : "java.io.File";
            String maxDepth = params != null ? params.getOrDefault("maxDepth", "20") : "20";
            // 默认保存位置（用户指定，支持系统属性覆盖）
            String reportPath = System.getProperty("dynamictracer.josearcher.output.path",
                                                 "./jos_result");

            return "{ " +
                   "System.out.println(\"开始josearcher搜索...\"); " +
                   "java.util.List keys = new java.util.ArrayList(); " +
                   "com.dynamictracer.vendor.josearcher.entity.Keyword.Builder kb = new com.dynamictracer.vendor.josearcher.entity.Keyword.Builder(); " +
                   "kb.setField_type(\"" + searchType + "\"); " +
                   "com.dynamictracer.vendor.josearcher.entity.Keyword key = kb.build(); " +
                   "keys.add(key); " +
                   "java.util.List blist = new java.util.ArrayList(); " +
                   "com.dynamictracer.vendor.josearcher.entity.Blacklist.Builder bb = new com.dynamictracer.vendor.josearcher.entity.Blacklist.Builder(); " +
                   "bb.setField_type(\"" + blacklistType + "\"); " +
                   "com.dynamictracer.vendor.josearcher.entity.Blacklist bl = bb.build(); " +
                   "blist.add(bl); " +
                   "com.dynamictracer.vendor.josearcher.searcher.SearchRequstByBFS searcher = new com.dynamictracer.vendor.josearcher.searcher.SearchRequstByBFS(Thread.currentThread(), keys); " +
                   "searcher.setBlacklists(blist); " +
                   "searcher.setIs_debug(false); " +
                   "searcher.setOutput_format(\"JSON\"); " +
                   "searcher.setUse_timestamp(false); " +
                   "searcher.setReport_save_path(\"" + reportPath + "\"); " +
                   "searcher.setInclude_stacktrace(true); " +
                   "searcher.setStacktrace_depth(20); " +
                   "String __cls = \"" + className + "\"; " +
                   "String __short = (__cls.lastIndexOf('.')>=0) ? __cls.substring(__cls.lastIndexOf('.')+1) : __cls; " +
                   "searcher.setOutput_basename(\"" + breakpointId + "-\" + __short); " +
                   "searcher.setSearch_tag(\"" + breakpointId + "\"); " +
                   "searcher.setMax_search_depth(" + maxDepth + "); " +
                   "searcher.searchObject(); " +
                   "String __res = searcher.getResult_file(); " +
                   "com.dynamictracer.core.HighPerformanceSQLiteLogger.getInstance().logBreakpoint(\"" + breakpointId + "\", \"" + className + "\", \"" + methodName + "\", \"BEFORE\", \"jos_result_file=\" + __res, null, \"EXECUTED\"); " +
                   "System.out.println(\"josearcher搜索执行完成，结果: \" + __res); " +
                   "}";
        } else if ("PARAMETER_DUMP".equals(templateName)) {
            return "System.out.println(\"方法参数: \" + java.util.Arrays.toString($args)); " +
                   "for (int i = 0; i < $args.length; i++) { " +
                   "  if ($args[i] != null) { " +
                   "    System.out.println(\"  参数[\" + i + \"]: \" + $args[i].getClass().getName() + \" = \" + $args[i]); " +
                   "  } else { " +
                   "    System.out.println(\"  参数[\" + i + \"]: null\"); " +
                   "  } " +
                   "}";
        } else if ("STACK_TRACE".equals(templateName)) {
            return "StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace(); " +
                   "System.out.println(\"调用堆栈:\"); " +
                   "for (int i = 0; i < Math.min(stackTrace.length, 5); i++) { " +
                   "  System.out.println(\"  \" + stackTrace[i]); " +
                   "}";
        } else if ("DYNAMIC_CLASS_INJECTION".equals(templateName)) {
            // 动态类注入模板
            String targetClassName = params != null ? params.getOrDefault("targetClassName", "com.pwn.MaliciousClass") : "com.pwn.MaliciousClass";
            String classBase64 = params != null ? params.getOrDefault("classBase64", "") : "";

            return "try { " +
                   // 检查类是否已存在
                   "try { " +
                   "  Class.forName(\\\"" + targetClassName + "\\\"); " +
                   "  System.out.println(\\\"类 " + targetClassName + " 已存在，跳过注入\\\"); " +
                   "} catch (ClassNotFoundException cnfe) { " +
                   // 类不存在，开始注入
                   "  System.out.println(\\\"开始注入类: " + targetClassName + "\\\"); " +
                   "  if (\\\"" + classBase64 + "\\\".isEmpty()) { " +
                   "    System.out.println(\\\"错误: 未提供类字节码(classBase64参数)\\\"); " +
                   "  } else { " +
                   // 解码 Base64 字节码
                   "    byte[] classBytes = java.util.Base64.getDecoder().decode(\\\"" + classBase64 + "\\\"); " +
                   // 获取当前线程的 ClassLoader
                   "    ClassLoader currentLoader = Thread.currentThread().getContextClassLoader(); " +
                   // 使用反射调用 defineClass 方法
                   "    java.lang.reflect.Method defineClassMethod = ClassLoader.class.getDeclaredMethod(\\\"defineClass\\\", String.class, byte[].class, int.class, int.class); " +
                   "    defineClassMethod.setAccessible(true); " +
                   "    Class<?> injectedClass = (Class<?>) defineClassMethod.invoke(currentLoader, \\\"" + targetClassName + "\\\", classBytes, 0, classBytes.length); " +
                   "    System.out.println(\\\"成功注入类: \\\" + injectedClass.getName()); " +
                   "  } " +
                   "} " +
                   "} catch (Exception e) { " +
                   "  System.err.println(\\\"类注入失败: \\\" + e.getMessage()); " +
                   "  e.printStackTrace(); " +
                   "}";
        }
        return "System.out.println(\"未知模板: " + templateName + "\");";
    }

    /**
     * 从文件加载代码
     */
    private String loadCodeFromFile(String filePath) {
        try {
            java.io.File file = new java.io.File("src/main/resources/rules/" + filePath);
            if (!file.exists()) {
                file = new java.io.File(filePath);
                if (!file.exists()) {
                    return "System.out.println(\"代码文件未找到: " + filePath + "\");";
                }
            }

            java.util.Scanner scanner = new java.util.Scanner(file);
            StringBuilder code = new StringBuilder();
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.startsWith("//") && !line.isEmpty()) { // 跳过注释和空行
                    code.append(line).append(" ");
                }
            }
            scanner.close();
            return code.toString();
        } catch (Exception e) {
            return "System.err.println(\"加载代码文件失败: " + e.getMessage() + "\");";
        }
    }

    /**
     * 根据ID查找断点配置
     */
    private BreakpointConfig findBreakpointConfigById(String breakpointId) {
        for (List<BreakpointConfig> configs : classBreakpoints.values()) {
            for (BreakpointConfig config : configs) {
                if (config.getId().equals(breakpointId)) {
                    return config;
                }
            }
        }
        return null;
    }

    /**
     * 生成默认断点代码（兼容性）
     */
    private String generateDefaultBreakpointCode(String breakpointId, String className, String methodName) {
        return "{ " +
               "  System.out.println(\"断点触发 [" + breakpointId + "] \" + \"" + className + "." + methodName + "\"); " +
               "}";
    }

    /**
     * 检查类是否在黑名单中
     */
    private boolean isBlacklisted(String className) {
        for (String blacklistedPrefix : BLACKLISTED_CLASSES) {
            if (className.startsWith(blacklistedPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取断点统计信息
     */
    public static String getStatistics() {
        return "断点触发总次数: " + breakpointTriggerCount.get();
    }
}
