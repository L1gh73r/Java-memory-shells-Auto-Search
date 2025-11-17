/**
 * @name 查找public可继承且被实例化存储并调用特定参数方法的类（参数过滤版）
 * @description 查找满足以下条件的类：
 *              1. public声明，可继承（非final）
 *              2. 在别的类中被实例化且被储存为field声明的对象
 *              3. 实例化后某方法被调用
 *              4. 调用的方法参数类型包含request或response关键字
 * @kind table
 * @id java/inheritable-instantiated-classes-with-request-response
 * @tags maintainability
 */

import java
import semmle.code.java.dataflow.DataFlow

/** 
 * 包名筛选模块 - 可以根据需要调整包名范围
 */
predicate isTargetPackage(RefType rt) {
    // 只包含Spring相关包
    (rt.getPackage().getName().matches("org.springframework%") or
     rt.getPackage().getName().matches("org.spring%") or
     rt.getPackage().getName().matches("org.springframework.boot%"
     ))
    and
    // 同时排除系统包
    (not rt.getPackage().getName().matches("java.%") and
     not rt.getPackage().getName().matches("javax.%") and
     not rt.getPackage().getName().matches("sun.%")
     )
}
 

/** 
 * 选择符合条件的类：public，非final，非abstract，非匿名 
 */
class RelevantClass extends RefType {
    RelevantClass() {
        (
           this.isPublic() and
           not this.isFinal() and
           not this.isAbstract() and
           not this.getName().regexpMatch(".*\\$\\d+.*") and // 排除匿名类
           not this.getName().matches("%Exception%") and // 排除包含'Exception'
           not this.getName().matches("Object") and
           this.getName().regexpMatch("[A-Za-z0-9]+") // 只包含字母和数字
           and
           isTargetPackage(this)
        )
        
    }
}


/**
 * 检查方法的参数类型是否包含request或response关键字
 */
predicate hasRequestOrResponseParameter(Method m) {
    exists(Type paramType |
        paramType = m.getAParamType() and
        (
            // 检查类型名称
            paramType.getName().toLowerCase().matches("%request%") or
            paramType.getName().toLowerCase().matches("%response%") or
            // 如果是RefType，检查完整限定名
            exists(RefType rt | rt = paramType and
                (rt.getQualifiedName().toLowerCase().matches("%request%") or
                 rt.getQualifiedName().toLowerCase().matches("%response%")))
        )
    )
}


/** 
 * 查找拥有目标类型字段且调用该字段方法的类
 */
predicate hasFieldAndUsesIt(RefType ownerClass, RelevantClass fieldType, Field f, Call ma) {
    // 字段属于ownerClass
    f.getDeclaringType() = ownerClass and
    not f.isFinal() and
    // 字段类型可以是fieldType，或者fieldType实现了字段的接口类型
    (f.getType() = fieldType or 
     fieldType.getASourceSupertype*() = f.getType() or
     f.getType().(RefType).getASourceSupertype*() = fieldType) and
    // 字段必须通过实例化或getBean等方式获取
    exists(Expr assignment |
        assignment = f.getAnAssignedValue() and
        (assignment instanceof ConstructorCall or
         assignment.(MethodCall).getMethod().getName().matches("%getBean%") or
         assignment.(MethodCall).getMethod().getName().matches("%getInstance%") or
         assignment.(MethodCall).getMethod().getName().matches("%create%"))
    ) and
    // 方法调用的qualifier是该字段
    ma.getQualifier().(VarAccess).getVariable() = f and
    // 确保方法属于fieldType或其相关类型
    (ma.getCallee().getDeclaringType() = fieldType or 
     fieldType.getASourceSupertype*() = ma.getCallee().getDeclaringType() or
     ma.getCallee().getDeclaringType().(RefType).getASourceSupertype*() = fieldType)
}
 
from RefType ownerClassName, RelevantClass fieldType, Field f, Call methodCall
where 
      hasFieldAndUsesIt(ownerClassName, fieldType, f, methodCall) and
      // 确保ownerClass也是Spring相关的类
      isTargetPackage(ownerClassName) and
      // 检查方法参数是否包含request或response关键字
      hasRequestOrResponseParameter(methodCall.getCallee())
select ownerClassName.getQualifiedName() as ownerClass, 
       f.getName() as fieldName, 
       fieldType.getQualifiedName() as fieldTypeName, 
       methodCall.getCallee().getName() as calledMethod,
       methodCall.getCallee().getSignature() as methodSignature

