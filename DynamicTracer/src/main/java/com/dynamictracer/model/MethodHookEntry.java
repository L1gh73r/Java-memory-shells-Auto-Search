package com.dynamictracer.model;

/**
 * 表示需要hook的方法信息的数据模型
 */
public class MethodHookEntry {
    private String ownerClass;
    private String fieldName;
    private String fieldTypeName;
    private String calledMethod;

    public MethodHookEntry() {
    }

    public MethodHookEntry(String ownerClass, String fieldName, String fieldTypeName, String calledMethod) {
        this.ownerClass = ownerClass;
        this.fieldName = fieldName;
        this.fieldTypeName = fieldTypeName;
        this.calledMethod = calledMethod;
    }

    public String getOwnerClass() {
        return ownerClass;
    }

    public void setOwnerClass(String ownerClass) {
        this.ownerClass = ownerClass;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldTypeName() {
        return fieldTypeName;
    }

    public void setFieldTypeName(String fieldTypeName) {
        this.fieldTypeName = fieldTypeName;
    }

    public String getCalledMethod() {
        return calledMethod;
    }

    public void setCalledMethod(String calledMethod) {
        this.calledMethod = calledMethod;
    }

    @Override
    public String toString() {
        return "MethodHookEntry{" +
                "ownerClass='" + ownerClass + '\'' +
                ", fieldName='" + fieldName + '\'' +
                ", fieldTypeName='" + fieldTypeName + '\'' +
                ", calledMethod='" + calledMethod + '\'' +
                '}';
    }

    /**
     * 生成用于识别需要hook的类的键
     * @return 类名
     */
    public String getTargetClassName() {
        return fieldTypeName;
    }

    /**
     * 生成用于识别需要hook的方法的键
     * @return 方法名
     */
    public String getTargetMethodName() {
        return calledMethod;
    }
}
