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

    private DynamicMethodHookRule globalHookRule;
    
    private BreakpointRule breakpointRule;

    public DefaultTransformer() {
        // load csv file
        loadCsvHookRules();
        
        loadBreakpointRules();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

        String dotClassName = className.replace("/", ".");
        try {

            if (shouldDump(dotClassName)) {
                saveBytecode(dotClassName, classfileBuffer, "original");
            }

            // is block action open
            boolean flag = false;

            // check black list
            if (Blacklist.CLASS_BLACKLIST.contains(dotClassName) && flag) {
                System.out.println("Blocked class: " + dotClassName);
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

            // check csv rules
            if (csvHookEntries.containsKey(dotClassName) && globalHookRule != null) {
//                System.out.println("doClassName (CSV规则): " + dotClassName);
                byte[] result = globalHookRule.modify(loader, dotClassName, classBeingRedefined, classfileBuffer);

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
     * load csv hook rules
     */
    private void loadCsvHookRules() {
        try {
            // get csv file path
            String csvPath = System.getProperty("ctfagent.csv.path", "data/test2.csv");

            System.out.println("尝试加载CSV hook配置文件: " + csvPath);

            // parse csv file
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
