package com.dynamictracer.core;

import com.dynamictracer.model.MethodHookEntry;
import com.dynamictracer.rule.DynamicMethodHookRule;
import com.dynamictracer.rule.BreakpointRule;
import com.dynamictracer.utils.CsvParser;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultTransformer implements ClassFileTransformer {
    private final Map<String, ClassModifier> modifiers = new HashMap<>();
    private final Map<String, List<MethodHookEntry>> csvHookEntries = new HashMap<>();
    private final Map<String, List<MethodHookEntry>> dynamicParentHooks = new HashMap<>();

    // 全局的DynamicMethodHookRule实例，统一管理进度
    private DynamicMethodHookRule globalHookRule;
    
    // 断点规则实例
    private BreakpointRule breakpointRule;

    public DefaultTransformer() {
        // 加载CSV配置的hook规则
        loadCsvHookRules();
        
        // 加载断点规则
        loadBreakpointRules();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

        String dotClassName = className.replace("/", ".");
        try {

            // 如果需要dump原始字节码，则输出到文件
            if (shouldDump(dotClassName)) {
                saveBytecode(dotClassName, classfileBuffer, "original");
            }

            // 决定是否需要进行拦截，默认不开启拦截，只进行监测
            boolean flag = false;

            // 检查黑名单
            if (Blacklist.CLASS_BLACKLIST.contains(dotClassName) && flag) {
                System.out.println("Blocked class: " + dotClassName);
                // 修改字节码，抛出异常
                ClassPool pool = ClassPool.getDefault();
                pool.appendClassPath(new javassist.LoaderClassPath(loader));
                CtClass cc = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
                for (CtConstructor constructor : cc.getDeclaredConstructors()) {
                    constructor.setBody("{ throw new com.dynamictracer.exception.BlacklistedClassException(\"" + dotClassName + "\"); }");
                }
                for (CtMethod method : cc.getDeclaredMethods()) {
                    method.setBody("{ throw new com.dynamictracer.exception.BlacklistedClassException(\"" + dotClassName + "\"); }");
                }
                return cc.toBytecode();
            }

            // 如果类名匹配传统规则，才进行处理
            if (modifiers.containsKey(dotClassName)) {
//                System.out.println("doClassName (传统规则): " + dotClassName);
                ClassPool pool = ClassPool.getDefault();
                pool.appendClassPath(new javassist.LoaderClassPath(loader));
                CtClass cc = pool.makeClass(new ByteArrayInputStream(classfileBuffer));

                saveBytecode("javax.servlet.http.HttpServlet", classfileBuffer, "original");

                ClassModifier modifier = modifiers.get(dotClassName);
                if (modifier != null) {
                    return modifier.modify(loader, dotClassName, classBeingRedefined, cc.toBytecode());
                }
            }

            // 检查CSV配置的hook规则
            if (csvHookEntries.containsKey(dotClassName) && globalHookRule != null) {
//                System.out.println("doClassName (CSV规则): " + dotClassName);
                byte[] result = globalHookRule.modify(loader, dotClassName, classBeingRedefined, classfileBuffer);

                // 获取需要额外hook的父类
                Map<String, List<MethodHookEntry>> parentClassesToHook = globalHookRule.getParentClassesToHook();
                if (parentClassesToHook != null && !parentClassesToHook.isEmpty()) {
//                    System.out.println("发现需要hook的父类: " + parentClassesToHook.keySet());
                    dynamicParentHooks.putAll(parentClassesToHook);
                }

                return result;
            }

            // 检查是否是需要动态hook的父类
            if (dynamicParentHooks.containsKey(dotClassName) && globalHookRule != null) {
//                System.out.println("doClassName (动态父类hook): " + dotClassName);
                // 处理完后移除，避免重复处理
                dynamicParentHooks.remove(dotClassName);
                return globalHookRule.modify(loader, dotClassName, classBeingRedefined, classfileBuffer);
            }
            
            // 检查断点规则
            if (breakpointRule != null) {
                byte[] breakpointResult = breakpointRule.modify(loader, dotClassName, classBeingRedefined, classfileBuffer);
                if (breakpointResult != null && breakpointResult != classfileBuffer) {
//                    System.out.println("doClassName (断点规则): " + dotClassName);
                    return breakpointResult;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 加载CSV配置的hook规则
     */
    private void loadCsvHookRules() {
        try {
            // 获取CSV文件路径，可以通过系统属性或者默认路径
            String csvPath = System.getProperty("ctfagent.csv.path", "data/test2.csv");

            System.out.println("尝试加载CSV hook配置文件: " + csvPath);

            // 解析CSV文件
            List<MethodHookEntry> allEntries = CsvParser.parseMethodHookCsv(csvPath);
            System.out.println("成功解析 " + allEntries.size() + " 个hook条目");

            // 按类名分组
            Map<String, List<MethodHookEntry>> groupedEntries = CsvParser.groupByClassName(allEntries);
            csvHookEntries.putAll(groupedEntries);

            System.out.println("已注册 " + csvHookEntries.size() + " 个类的hook规则:");

            // 创建全局的DynamicMethodHookRule实例，基于所有的hook条目
            this.globalHookRule = new DynamicMethodHookRule(allEntries);

        } catch (Exception e) {
            System.err.println("加载CSV hook配置失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 加载断点规则
     */
    private void loadBreakpointRules() {
        try {
            // 检查是否启用断点功能
            boolean enableBreakpoints = Boolean.parseBoolean(
                System.getProperty("dynamictracer.breakpoint.enabled", "true")
            );
            
            if (!enableBreakpoints) {
                System.out.println("⚪ 断点功能已禁用（使用 -Ddynamictracer.breakpoint.enabled=true 启用）");
                return;
            }
            
            System.out.println("🎯 正在初始化断点规则...");
            
            // 创建断点规则实例
            this.breakpointRule = new BreakpointRule();
            
            System.out.println("✅ 断点规则初始化完成");
            
        } catch (Exception e) {
            System.err.println("❌ 加载断点规则失败: " + e.getMessage());
            e.printStackTrace();
            this.breakpointRule = null;
        }
    }

    /**
     * 判断是否需要dump指定类的字节码，
     * 通过系统属性 "ctfagent.dump" 来指定要dump的类名
     */
    private boolean shouldDump(String className) {
        String dumpClass = "sun.print.UnixPrintJob";
        return dumpClass != null && dumpClass.equals(className);
    }

    /**
     * 将字节码保存到 dump 目录下，文件名格式为 "类名_suffix.class"
     *
     * @param className 类名（点分隔格式）
     * @param bytecode  字节码数据
     * @param suffix    描述类型，比如 "original" 或 "modified"
     */
    private void saveBytecode(String className, byte[] bytecode, String suffix) {
        try {
            File dumpDir = new File("dump");
            if (!dumpDir.exists()) {
                dumpDir.mkdirs();
            }
            // 将包名中的点替换为下划线，避免文件夹问题
            String fileName = className.replace('.', '_') + "_" + suffix + ".class";
            File file = new File(dumpDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytecode);
            }
            System.out.println("Dumped bytecode for " + className + " (" + suffix + ") to " + file.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
