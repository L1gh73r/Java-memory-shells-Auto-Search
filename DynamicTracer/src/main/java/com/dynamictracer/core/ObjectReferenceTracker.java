package com.dynamictracer.core;

import com.dynamictracer.model.MethodHookEntry;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象引用追踪器 - 用于分析当前对象被哪些地方引用
 *
 * 功能：
 * 1. 静态分析信息利用：基于CSV数据建立引用关系映射
 * 2. 堆栈信息分析：从调用堆栈中寻找对象引用
 */
public class ObjectReferenceTracker {

    // 静态引用映射：目标类型 -> 可能持有该类型对象的类信息列表
    private static final Map<String, List<MethodHookEntry>> staticReferenceMap = new ConcurrentHashMap<>();

    // 引用搜索结果
    public static class ReferenceInfo {
        private final String holderClass;
        private final String fieldName;
        private final String referenceType;
        private final String discoveryMethod; // "static" 或 "stack"
        private final Object holderObject;

        public ReferenceInfo(String holderClass, String fieldName, String referenceType,
                             String discoveryMethod, Object holderObject) {
            this.holderClass = holderClass;
            this.fieldName = fieldName;
            this.referenceType = referenceType;
            this.discoveryMethod = discoveryMethod;
            this.holderObject = holderObject;
        }

        public String getHolderClass() { return holderClass; }
        public String getFieldName() { return fieldName; }
        public String getReferenceType() { return referenceType; }
        public String getDiscoveryMethod() { return discoveryMethod; }
        public Object getHolderObject() { return holderObject; }

        @Override
        public String toString() {
            return String.format("%s.%s(%s) via %s", holderClass, fieldName, referenceType, discoveryMethod);
        }
    }

    /**
     * 初始化静态引用映射
     * @param hookEntries CSV解析出的hook条目
     */
    public static void initializeStaticReferenceMap(List<MethodHookEntry> hookEntries) {
        staticReferenceMap.clear();

        for (MethodHookEntry entry : hookEntries) {
            String targetType = entry.getTargetClassName();
            staticReferenceMap.computeIfAbsent(targetType, k -> new ArrayList<>()).add(entry);
        }

        System.out.println("🔗 静态引用映射已初始化，涵盖 " + staticReferenceMap.size() + " 种目标类型");
    }

    /**
     * 追踪指定对象的引用关系
     * @param targetObject 目标对象
     * @param targetClassName 目标对象的类名
     * @return 发现的引用信息列表
     */
    public static List<ReferenceInfo> trackObjectReferences(Object targetObject, String targetClassName) {
        List<ReferenceInfo> references = new ArrayList<>();

        try {
            // 第一层：静态分析信息利用
            references.addAll(findStaticReferences(targetObject, targetClassName));

            // 第二层：堆栈信息分析
            references.addAll(findStackReferences(targetObject, targetClassName));

        } catch (Exception e) {
            System.err.println("引用追踪过程中出错: " + e.getMessage());
        }

        return references;
    }

    /**
     * 第一层：基于静态分析信息查找引用
     */
    private static List<ReferenceInfo> findStaticReferences(Object targetObject, String targetClassName) {
        List<ReferenceInfo> references = new ArrayList<>();

        // 从静态映射中查找可能的持有者
        List<MethodHookEntry> possibleHolders = staticReferenceMap.get(targetClassName);
        if (possibleHolders == null || possibleHolders.isEmpty()) {
            return references;
        }

        // 获取当前线程，作为搜索起点
        Thread currentThread = Thread.currentThread();
        Set<Object> visited = new HashSet<>();

        for (MethodHookEntry holder : possibleHolders) {
            try {
                // 第一层已经通过CSV找到了可能的holder，现在进行第二层验证
                // 第二层：堆栈信息分析 - 从调用堆栈中寻找实际的holder实例
                Object holderInstance = findHolderInStackFrames(holder, targetObject);

                if (holderInstance != null) {
                    // 检查该holder对象是否真的持有目标对象
                    if (holderInstance instanceof StackFrameHolder || 
                        checkFieldReference(holderInstance, holder.getFieldName(), targetObject)) {
                        
                        String discoveryMethod = holderInstance instanceof StackFrameHolder ? "stack-analysis" : "verified";
                        references.add(new ReferenceInfo(
                                holder.getOwnerClass(),
                                holder.getFieldName(),
                                holder.getFieldTypeName(),
                                discoveryMethod,
                                holderInstance
                        ));
                    }
                }
            } catch (Exception e) {
                // 静默处理异常
            }
        }
        return references;
    }

    /**
     * 第二层：堆栈信息分析
     * 从调用堆栈的每个方法中尝试获取局部变量和实例字段，寻找holder实例
     */
    private static Object findHolderInStackFrames(MethodHookEntry holder, Object targetObject) {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            
            // 遍历调用堆栈中的每个方法
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                
                // 跳过我们自己的方法和一些系统方法
                if (className.startsWith("com.dynamictracer") || 
                    className.startsWith("java.") || 
                    className.startsWith("sun.")) {
                    continue;
                }
                
                // 尝试从这个堆栈帧中寻找holder实例
                Object holderInstance = analyzeStackFrame(className, methodName, holder, targetObject);
                if (holderInstance != null) {
                    return holderInstance;
                }
            }
            
        } catch (Exception e) {
            // 静默处理异常
        }
        
        return null;
    }
    
    /**
     * 分析单个堆栈帧，寻找可能的holder实例
     */
    private static Object analyzeStackFrame(String className, String methodName, 
                                          MethodHookEntry holder, Object targetObject) {
        try {
            // 策略1: 如果堆栈帧的类就是holder类，那么这个类的实例可能就是我们要找的holder
            if (className.equals(holder.getOwnerClass())) {
                // 找到了holder类在调用堆栈中，说明存在一个该类的实例正在执行
                // 由于无法直接从堆栈帧获取对象实例，我们进行类型验证
                if (isValidHolderReference(holder, targetObject)) {
                    // 返回一个验证标记，表示在堆栈中找到了合理的holder类调用
                    return new StackFrameHolder(className, methodName, holder.getFieldName());
                }
            }
            
            // 策略2: 分析是否为相关的Spring上下文类
            if (isSpringContextClass(className) && holder.getOwnerClass().contains("ApplicationContext")) {
                if (isValidHolderReference(holder, targetObject)) {
                    return new StackFrameHolder(className, methodName, holder.getFieldName());
                }
            }
            
        } catch (Exception e) {
            // 静默处理异常
        }
        
        return null;
    }
    
    /**
     * 检查是否为有效的holder引用关系
     */
    private static boolean isValidHolderReference(MethodHookEntry holder, Object targetObject) {
        String expectedType = holder.getFieldTypeName();
        String actualType = targetObject.getClass().getName();
        
        // 精确类型匹配
        if (expectedType.equals(actualType)) {
            return true;
        }
        
        // 检查继承关系
        try {
            Class<?> expectedClass = Class.forName(expectedType);
            return expectedClass.isAssignableFrom(targetObject.getClass());
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * 检查是否为Spring上下文相关类
     */
    private static boolean isSpringContextClass(String className) {
        return className.contains("ApplicationContext") || 
               className.contains("BeanFactory") ||
               className.contains("ConfigurableApplicationContext");
    }
    
    /**
     * 堆栈帧持有者标记类
     */
    private static class StackFrameHolder {
        private final String className;
        private final String methodName;
        private final String fieldName;
        
        public StackFrameHolder(String className, String methodName, String fieldName) {
            this.className = className;
            this.methodName = methodName;
            this.fieldName = fieldName;
        }
        
        @Override
        public String toString() {
            return "StackFrameHolder{" + className + "." + methodName + " -> field:" + fieldName + "}";
        }
    }
    

    /**
     * 第二层：从堆栈信息中查找引用
     */
    private static List<ReferenceInfo> findStackReferences(Object targetObject, String targetClassName) {
        List<ReferenceInfo> references = new ArrayList<>();

        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

            // 分析堆栈中的每一帧（跳过tracker本身的帧）
            for (int i = 5; i < Math.min(stackTrace.length, 15); i++) {
                StackTraceElement frame = stackTrace[i];

                try {
                    Class<?> frameClass = Class.forName(frame.getClassName());

                    // 检查该类的所有实例字段
                    List<ReferenceInfo> frameRefs = analyzeClassForReferences(
                            frameClass, targetObject, targetClassName, "stack"
                    );
                    references.addAll(frameRefs);

                } catch (ClassNotFoundException e) {
                    // 类无法加载时跳过
                    continue;
                } catch (Exception e) {
                    // 其他异常也跳过，保证稳定性
                    continue;
                }
            }

        } catch (Exception e) {
            System.err.println("堆栈分析失败: " + e.getMessage());
        }

        return references;
    }

    /**
     * 在指定上下文中搜索特定类型的对象
     */
    private static Object findObjectInContext(Object root, String targetClassName, Set<Object> visited, int maxDepth) {
        if (root == null || maxDepth <= 0 || visited.contains(root)) {
            return null;
        }

        visited.add(root);

        try {
            // 检查当前对象是否就是目标类型
            if (root.getClass().getName().equals(targetClassName)) {
                return root;
            }

            // 递归搜索所有字段
            Field[] fields = getAllFields(root.getClass());
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(root);

                    if (fieldValue != null && !isBlacklistedType(fieldValue.getClass())) {
                        Object found = findObjectInContext(fieldValue, targetClassName, visited, maxDepth - 1);
                        if (found != null) {
                            return found;
                        }
                    }
                } catch (Exception e) {
                    // 字段访问失败时继续
                    continue;
                }
            }
        } catch (Exception e) {
            // 处理异常
        }

        return null;
    }

    /**
     * 检查指定字段是否引用了目标对象
     */
    private static boolean checkFieldReference(Object holder, String fieldName, Object targetObject) {
        try {
            Field field = findFieldByName(holder.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                Object fieldValue = field.get(holder);
                return fieldValue == targetObject; // 使用引用比较
            }
        } catch (Exception e) {
            // 访问失败
        }
        return false;
    }

    /**
     * 分析类的所有字段，寻找可能的引用
     */
    private static List<ReferenceInfo> analyzeClassForReferences(Class<?> clazz, Object targetObject,
                                                                 String targetClassName, String discoveryMethod) {
        List<ReferenceInfo> references = new ArrayList<>();

        try {
            Field[] fields = getAllFields(clazz);

            for (Field field : fields) {
                try {
                    // 检查字段类型是否匹配
                    Class<?> fieldType = field.getType();
                    if (fieldType.getName().equals(targetClassName) ||
                            fieldType.isAssignableFrom(targetObject.getClass())) {

                        references.add(new ReferenceInfo(
                                clazz.getName(),
                                field.getName(),
                                fieldType.getName(),
                                discoveryMethod,
                                null // 堆栈分析时通常无法获取实例
                        ));
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            // 处理异常
        }

        return references;
    }

    /**
     * 获取类的所有字段（包括继承的私有字段）
     */
    private static Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();

        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }

        return fields.toArray(new Field[0]);
    }

    /**
     * 根据名称查找字段
     */
    private static Field findFieldByName(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 检查类型是否在黑名单中
     */
    private static boolean isBlacklistedType(Class<?> clazz) {
        String className = clazz.getName();
        return className.startsWith("java.lang.") ||
                className.startsWith("java.util.concurrent.") ||
                className.startsWith("java.io.") ||
                className.startsWith("sun.") ||
                className.startsWith("com.sun.") ||
                clazz.isPrimitive();
    }

    /**
     * 格式化引用信息为可读字符串
     */
    public static String formatReferences(List<ReferenceInfo> references) {
        if (references.isEmpty()) {
            return null; // 没有找到引用直接返回null，数据库中将存储为空值
        }

        StringBuilder sb = new StringBuilder();
        sb.append("发现 ").append(references.size()).append(" 个对象引用:\n");

        Map<String, List<ReferenceInfo>> groupedByMethod = new HashMap<>();
        for (ReferenceInfo ref : references) {
            groupedByMethod.computeIfAbsent(ref.getDiscoveryMethod(), k -> new ArrayList<>()).add(ref);
        }

        for (Map.Entry<String, List<ReferenceInfo>> entry : groupedByMethod.entrySet()) {
            sb.append("  [").append(entry.getKey()).append("分析] ").append(entry.getValue().size()).append("个:\n");
            for (ReferenceInfo ref : entry.getValue()) {
                sb.append("    - ").append(ref.toString()).append("\n");
            }
        }

        return sb.toString();
    }
}