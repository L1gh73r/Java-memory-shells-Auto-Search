package com.dynamictracer.rule;

import com.dynamictracer.core.ClassModifier;
import com.dynamictracer.core.HighPerformanceSQLiteLogger;
import com.dynamictracer.core.ObjectReferenceTracker;
import com.dynamictracer.model.MethodHookEntry;
import javassist.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态方法Hook规则，根据CSV配置来hook指定的方法
 */
public class DynamicMethodHookRule implements ClassModifier {
    
    private final List<MethodHookEntry> hookEntries;
    private final HighPerformanceSQLiteLogger sqliteLogger;
    private final Map<String, List<MethodHookEntry>> parentClassesToHook = new HashMap<>();
    
    // 全局进度跟踪 - 所有实例共享
    private static volatile int globalTotalClasses = 0;
    private static volatile int globalProcessedClasses = 0;
    private static volatile int globalSuccessfulHooks = 0;
    private static volatile boolean progressInitialized = false;
    private static volatile long lastProgressUpdate = 0;
    private static final Object progressLock = new Object();
    private static final long PROGRESS_UPDATE_INTERVAL = 100; // 最小更新间隔（毫秒）
    
    // 不应该被hook的类黑名单
    private static final Set<String> BLACKLISTED_CLASSES = new HashSet<>(Arrays.asList(
        "java.",  // Java核心类
        "sun.",   // Sun内部类
        "javax.", // Java扩展类
        "com.sun.", // Sun内部类
        "org.springframework.boot.context.properties.bind.", // Spring Boot属性绑定相关
        "org.springframework.util.ConcurrentReferenceHashMap",
        "javassist.", // Javassist自身的类
        "com.dynamictracer." // 我们自己的Agent类
    ));
    
    // ThreadLocal来跟踪每个线程的hook状态，防止重入
    private static final ThreadLocal<Boolean> inHook = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    // 引用追踪功能开关（可通过系统属性控制）
    private final boolean enableReferenceTracking;
    
    public static boolean isInHook() {
        return inHook.get();
    }
    
    public static void setInHook(boolean value) {
        inHook.set(value);
    }
    
    public DynamicMethodHookRule(List<MethodHookEntry> hookEntries) {
        this.hookEntries = hookEntries;
        this.sqliteLogger = HighPerformanceSQLiteLogger.getInstance();

        // 读取引用追踪开关
        this.enableReferenceTracking = Boolean.parseBoolean(
                System.getProperty("ctfagent.reference.tracking", "false")
        );

        // 初始化全局进度（只在第一次时）
        synchronized (progressLock) {
            if (!progressInitialized) {
                // 计算需要处理的类数量
                Set<String> uniqueClasses = new HashSet<>();
                for (MethodHookEntry entry : hookEntries) {
                    uniqueClasses.add(entry.getTargetClassName());
                }

                // 初始化引用追踪器的静态映射
                if (enableReferenceTracking) {
                    ObjectReferenceTracker.initializeStaticReferenceMap(hookEntries);
                    System.out.println("🔗 对象引用追踪功能已启用");
                } else {
                    System.out.println("⚪ 对象引用追踪功能已禁用（使用 -Dctfagent.reference.tracking=true 启用）");
                }

                globalTotalClasses = uniqueClasses.size();
                globalProcessedClasses = 0;
                globalSuccessfulHooks = 0;
                progressInitialized = true;
                
                System.out.println("🚀 动态方法Hook规则已初始化");
                System.out.println("📊 预计处理 " + globalTotalClasses + " 个类，共 " + hookEntries.size() + " 个Hook点");
                System.out.println("💾 " + sqliteLogger.getStatistics());
            }
        }
    }
    
    /**
     * 获取需要额外hook的父类
     * @return 父类名到MethodHookEntry的映射
     */
    public Map<String, List<MethodHookEntry>> getParentClassesToHook() {
        return parentClassesToHook;
    }

    @Override
    public byte[] modify(ClassLoader loader, String className, Class<?> classBeingRedefined, byte[] classfileBuffer) {
        // 检查黑名单
        if (isBlacklisted(className)) {
            return classfileBuffer;
        }
        
        // 检查是否是我们需要hook的类
        boolean isTargetClass = false;
        for (MethodHookEntry entry : hookEntries) {
            if (className.equals(entry.getTargetClassName())) {
                isTargetClass = true;
                break;
            }
        }
        
        if (!isTargetClass) {
            return classfileBuffer;
        }
        
        // 更新全局进度
        int current;
        synchronized (progressLock) {
            current = ++globalProcessedClasses;
        }
        showProgress(current, className);
        
        try {
            ClassPool pool = ClassPool.getDefault();
            pool.appendClassPath(new javassist.LoaderClassPath(loader));
            
            CtClass cc = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
            boolean modified = false;

            // 遍历所有需要hook的条目，找到匹配当前类的
            for (MethodHookEntry entry : hookEntries) {
                if (className.equals(entry.getTargetClassName())) {
                    
                    try {
                        // 首先尝试获取当前类声明的方法
                        CtMethod[] declaredMethods = cc.getDeclaredMethods(entry.getTargetMethodName());
                        
                        // 如果当前类没有，尝试查找继承的方法
                        java.util.List<CtMethod> allFoundMethods = new java.util.ArrayList<>();
                        
                        if (declaredMethods.length > 0) {
                            for (CtMethod m : declaredMethods) {
                                allFoundMethods.add(m);
                            }
                        } else {
                            try {
                                // 尝试通过方法名查找（包括继承的）
                                CtMethod[] allMethods = cc.getMethods();
                                for (CtMethod m : allMethods) {
                                    if (m.getName().equals(entry.getTargetMethodName())) {
                                        String declaringClassName = m.getDeclaringClass().getName();
                                        
                                        // 如果方法在父类中，记录下需要hook的父类
                                        if (!declaringClassName.equals(className)) {
                                            
                                            // 检查父类是否在黑名单中
                                            if (isBlacklisted(declaringClassName)) {
                                                continue;
                                            }
                                            
                                            // 创建一个新的MethodHookEntry，目标类改为父类
                                            MethodHookEntry parentEntry = new MethodHookEntry(
                                                entry.getOwnerClass(),
                                                entry.getFieldName(),
                                                declaringClassName,  // 改为父类名
                                                entry.getCalledMethod()
                                            );
                                            
                                            // 添加到父类hook列表
                                            parentClassesToHook.computeIfAbsent(declaringClassName, 
                                                k -> new ArrayList<>()).add(parentEntry);
                                            
                                            // 不在当前类上进行hook
                                            continue;
                                        }
                                        
                                        allFoundMethods.add(m);
                                    }
                                }
                            } catch (Exception e) {
                                // 静默处理继承方法查找错误
                            }
                        }
                        
                        // 静默处理未找到方法的情况
                        if (allFoundMethods.isEmpty()) {
                            // 不输出详细信息，避免日志干扰
                        }
                        
                        // Hook找到的所有方法
                        for (CtMethod method : allFoundMethods) {
                            if (hookMethod(method, entry)) {
                                modified = true;
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("获取方法时出错: " + e.getMessage());
                        e.printStackTrace();
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
     * Hook指定的方法
     * 
     * @param method 要hook的方法
     * @param entry hook配置条目
     * @return 是否成功hook
     */
    private boolean hookMethod(CtMethod method, MethodHookEntry entry) {
        try {
            String className = method.getDeclaringClass().getName();
            String methodName = method.getName();
            String methodSignature = method.getSignature();
            
            // 检查是否为native方法
            if (javassist.Modifier.isNative(method.getModifiers())) {
                System.out.println("⚠️  跳过native方法: " + methodName);
                return false;
            }
            
            // 检查是否为final方法
            if (javassist.Modifier.isFinal(method.getModifiers())) {
                System.out.println("⚠️  跳过final方法: " + methodName);
                return false;
            }
            
            // 跳过Object类的基础方法，这些方法通常不应该被hook
            if (isObjectBaseMethod(methodName)) {
                System.out.println("⚠️  跳过Object基础方法: " + methodName);
                return false;
            }
            
            // 跳过可能导致问题的特殊方法
            if (isProblematicMethod(methodName, className)) {
                System.out.println("⚠️  跳过问题方法: " + className + "." + methodName);
                return false;
            }
            
            // 检查是否为abstract方法
            if (javassist.Modifier.isAbstract(method.getModifiers())) {
                System.out.println("⚠️  跳过abstract方法: " + methodName);
                return false;
            }

            // 生成参数采集代码
            StringBuilder paramLogCode = new StringBuilder();
            paramLogCode.append("StringBuilder paramInfo = new StringBuilder();");
            
            // 获取参数类型
            CtClass[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                paramLogCode.append("paramInfo.append(\"参数").append(i + 1).append(": \");");
                
                // 检查参数类型，避免基础类型与null比较
                if (paramTypes[i].isPrimitive()) {
                    // 基础类型直接转换为字符串
                    paramLogCode.append("paramInfo.append(String.valueOf($").append(i + 1).append("));");
                } else {
                    // 对象类型需要null检查
                    paramLogCode.append("if ($").append(i + 1).append(" != null) {");
                    paramLogCode.append("  paramInfo.append($").append(i + 1).append(".toString());");
                    paramLogCode.append("} else {");
                    paramLogCode.append("  paramInfo.append(\"null\");");
                    paramLogCode.append("}");
                }
                paramLogCode.append("paramInfo.append(\", \");");
            }

            // 生成堆栈采集代码（优化为仅采集前5层，减少性能开销）
            String stackTraceCode = 
                "StringBuilder stackInfo = new StringBuilder();" +
                "StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();" +
                "for (int i = 0; i < Math.min(stackTrace.length, 10); i++) {" +
                "  stackInfo.append(stackTrace[i].toString()).append(\"\\n\");" +
                "}";

            // 新增：引用追踪代码（仅在启用时添加）
            String referenceTrackingCode = "";
            if (enableReferenceTracking) {
                // 检查方法是否为静态方法
                boolean isStatic = javassist.Modifier.isStatic(method.getModifiers());
                if (isStatic) {
                    // 静态方法中，无法使用this，直接禁用引用追踪
                    referenceTrackingCode = "StringBuilder referenceInfo = new StringBuilder();";
                } else {
                    // 非静态方法中，可以安全使用this
                    referenceTrackingCode =
                            "StringBuilder referenceInfo = new StringBuilder();" +
                                    "try {" +
                                    "  Object currentObj = this;" +  // 先赋值给变量，避免直接使用this
                                    "  if (currentObj != null) {" +
                                    "    java.util.List refList = com.dynamictracer.core.ObjectReferenceTracker.trackObjectReferences(" +
                                    "      currentObj, \"" + className + "\");" +
                                    "    String refResult = com.dynamictracer.core.ObjectReferenceTracker.formatReferences(refList);" +
                                    "    if (refResult != null) {" +
                                    "      referenceInfo.append(refResult);" +
                                    "    }" +
                                    "  }" +
                                    "} catch (Exception refEx) {" +
                                    "  // 引用追踪失败时保持空值" +
                                    "}";
                }
            } else {
                // 禁用时提供空的引用信息
                referenceTrackingCode = "StringBuilder referenceInfo = new StringBuilder();";
            }

            // 日志记录代码，增加引用信息参数
            String logCode = String.format(
                    "try {" +
                            "  com.dynamictracer.core.HighPerformanceSQLiteLogger.getInstance().logHookWithReferences(" +
                            "    \"%s\", \"%s\", \"%s\", \"%s\", \"%s\"," +
                            "    paramInfo.toString(), stackInfo.toString(), referenceInfo.toString());" +
                            "} catch (Exception logEx) {" +
                            "  System.err.println(\"SQLite日志记录出错: \" + logEx.getMessage());" +
                            "}",
                    className, methodName, methodSignature,
                    entry.getOwnerClass(), entry.getFieldName()
            );

            // 纯监控模式：只记录信息，完全不影响原方法执行
            // 防重入检查：如果正在hook中，直接跳过本次记录，但不影响原方法
            String reentryCheckCode = 
                "if (!com.dynamictracer.rule.DynamicMethodHookRule.isInHook()) { " +
                "  com.dynamictracer.rule.DynamicMethodHookRule.setInHook(true); " +
                "  try { ";

            String reentryCleanupCode =
                    "  } finally { com.dynamictracer.rule.DynamicMethodHookRule.setInHook(false); } " +
                    "}";


            // 组合完整的前置代码（纯监控模式，不影响原方法执行）
            String beforeCode = "{ " + reentryCheckCode +
                    paramLogCode.toString() + " " + // 参数信息收集
                    stackTraceCode + " " +          // 堆栈信息收集
                    referenceTrackingCode + " " +   // 引用追踪信息收集
                    logCode + " " +                 // 日志记录
                    reentryCleanupCode + " }";
            

            // 简化hook代码，参考其他rule的实现方式
            StringBuilder hookCode = new StringBuilder();
            
            // 防重入检查
            hookCode.append("if (!com.dynamictracer.rule.DynamicMethodHookRule.isInHook()) {\n");
            hookCode.append("  com.dynamictracer.rule.DynamicMethodHookRule.setInHook(true);\n");
            hookCode.append("  try {\n");
            
            // 参数信息收集
            hookCode.append("    StringBuilder paramInfo = new StringBuilder();\n");
            for (int i = 0; i < paramTypes.length; i++) {
                hookCode.append("    paramInfo.append(\"参数").append(i + 1).append(": \");\n");
                if (paramTypes[i].isPrimitive()) {
                    hookCode.append("    paramInfo.append(String.valueOf($").append(i + 1).append("));\n");
                } else {
                    hookCode.append("    if ($").append(i + 1).append(" != null) {\n");
                    hookCode.append("      paramInfo.append($").append(i + 1).append(".toString());\n");
                    hookCode.append("    } else {\n");
                    hookCode.append("      paramInfo.append(\"null\");\n");
                    hookCode.append("    }\n");
                }
                hookCode.append("    paramInfo.append(\", \");\n");
            }
            
            // 堆栈信息收集
            hookCode.append("    StringBuilder stackInfo = new StringBuilder();\n");
            hookCode.append("    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();\n");
            hookCode.append("    for (int i = 0; i < Math.min(stackTrace.length, 10); i++) {\n");
            hookCode.append("      stackInfo.append(stackTrace[i].toString()).append(\"\\n\");\n");
            hookCode.append("    }\n");
            
            // 引用追踪信息
            hookCode.append("    StringBuilder referenceInfo = new StringBuilder();\n");
            if (enableReferenceTracking && !javassist.Modifier.isStatic(method.getModifiers())) {
                hookCode.append("    try {\n");
                hookCode.append("      Object currentObj = this;\n");
                hookCode.append("      if (currentObj != null) {\n");
                hookCode.append("        java.util.List refList = com.dynamictracer.core.ObjectReferenceTracker.trackObjectReferences(currentObj, \"").append(className).append("\");\n");
                hookCode.append("        String refResult = com.dynamictracer.core.ObjectReferenceTracker.formatReferences(refList);\n");
                hookCode.append("        if (refResult != null) {\n");
                hookCode.append("          referenceInfo.append(refResult);\n");
                hookCode.append("        }\n");
                hookCode.append("      }\n");
                hookCode.append("    } catch (Exception refEx) {}\n");
            }
            
            // 日志记录
            hookCode.append("    try {\n");
            hookCode.append("      com.dynamictracer.core.HighPerformanceSQLiteLogger.getInstance().logHookWithReferences(\n");
            hookCode.append("        \"").append(className).append("\", \"").append(methodName).append("\", \"").append(methodSignature).append("\",\n");
            hookCode.append("        \"").append(entry.getOwnerClass()).append("\", \"").append(entry.getFieldName()).append("\",\n");
            hookCode.append("        paramInfo.toString(), stackInfo.toString(), referenceInfo.toString());\n");
            hookCode.append("    } catch (Exception logEx) {}\n");
            
            // 清理
            hookCode.append("  } finally {\n");
            hookCode.append("    com.dynamictracer.rule.DynamicMethodHookRule.setInHook(false);\n");
            hookCode.append("  }\n");
            hookCode.append("}\n");
            
            // System.out.println("Hook代码长度: " + hookCode.length());
            method.insertBefore(hookCode.toString());
            // System.out.println("📍 SQLite Hook代码已插入到方法开始处");
            
            // 打印详细的方法信息进行调试
            // System.out.println("🔍 Hook详情:");
            // System.out.println("   - 目标类: " + className);
            // System.out.println("   - 方法声明类: " + method.getDeclaringClass().getName());
            // System.out.println("   - 方法修饰符: " + javassist.Modifier.toString(method.getModifiers()));
            // System.out.println("   - 是否为继承方法: " + (!method.getDeclaringClass().getName().equals(className)));
            
            // 验证方法是否可以被重写
            if (!javassist.Modifier.isFinal(method.getModifiers()) && 
                !javassist.Modifier.isStatic(method.getModifiers()) &&
                !javassist.Modifier.isPrivate(method.getModifiers())) {

            } else {
                // System.out.println("   - 方法不能被重写: ⚠️");
            }
            
            // 增加全局成功hook计数
            synchronized (progressLock) {
                globalSuccessfulHooks++;
            }
            return true;
            
        } catch (Exception e) {
            System.err.println("Hook方法 " + method.getName() + " 时出错: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 检查类是否在黑名单中
     * 
     * @param className 类名
     * @return 是否在黑名单中
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
     * 检查是否为Object类的基础方法
     */
    private boolean isObjectBaseMethod(String methodName) {
        return "getClass".equals(methodName) ||
               "hashCode".equals(methodName) ||
               "equals".equals(methodName) ||
               "toString".equals(methodName) ||
               "notify".equals(methodName) ||
               "notifyAll".equals(methodName) ||
               "wait".equals(methodName) ||
               "finalize".equals(methodName) ||
               "clone".equals(methodName);
    }
    
    /**
     * 检查是否为可能导致问题的特殊方法
     */
    private boolean isProblematicMethod(String methodName, String className) {
        // 跳过类初始化方法
        if ("<clinit>".equals(methodName) || "<init>".equals(methodName)) {
            return true;
        }
        
        // // 跳过某些框架的敏感方法
        // if (className.contains("ClassLoader") && 
        //     ("loadClass".equals(methodName) || "defineClass".equals(methodName))) {
        //     return true;
        // }
        
        // // 跳过反射相关的敏感方法
        // if (className.contains("Reflection") || className.contains("Method")) {
        //     return true;
        // }
        
        return false;
    }
    
    /**
     * 显示Hook进度
     * 
     * @param current 当前处理的类数量
     * @param className 当前类名
     */
    private static void showProgress(int current, String className) {
        if (globalTotalClasses == 0) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        boolean shouldUpdate = false;
        boolean isComplete = current >= globalTotalClasses;
        
        synchronized (progressLock) {
            // 强制更新的条件：完成时 或 距离上次更新超过间隔时间
            if (isComplete || (currentTime - lastProgressUpdate) >= PROGRESS_UPDATE_INTERVAL) {
                shouldUpdate = true;
                lastProgressUpdate = currentTime;
            }
        }
        
        if (!shouldUpdate && !isComplete) {
            return;
        }
        
        // 限制百分比不超过100%
        double percentage = Math.min((double) current / globalTotalClasses * 100, 100.0);
        int barLength = 30;
        int filledLength = (int) (barLength * percentage / 100);
        
        StringBuilder progressBar = new StringBuilder();
        progressBar.append("[");
        for (int i = 0; i < barLength; i++) {
            if (i < filledLength) {
                progressBar.append("█");
            } else {
                progressBar.append("░");
            }
        }
        progressBar.append("]");
        
        // 简化类名显示
        String simpleClassName = className;
        if (simpleClassName.length() > 40) {
            simpleClassName = "..." + simpleClassName.substring(simpleClassName.length() - 37);
        }
    
        // 获取当前成功数量（线程安全）
        int currentSuccessful;
        synchronized (progressLock) {
            currentSuccessful = globalSuccessfulHooks;
        }
        
        System.out.printf("\r🔧 Hook进度: %s %.1f%% (%d/%d) | 成功: %d | %s", 
            progressBar.toString(), percentage, current, globalTotalClasses, currentSuccessful, simpleClassName);
        
        // 完成时换行（只在真正完成时显示）
        if (isComplete) {
            System.out.println();
            System.out.println("🎉 Hook完成! 总计处理 " + globalTotalClasses + " 个类，成功Hook " + currentSuccessful + " 个方法");
        }
    }
}
