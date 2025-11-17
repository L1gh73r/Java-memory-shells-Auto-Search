/**
 * @name 查找public可继承且被实例化存储并调用方法的类（修复版）
 * @description 查找满足以下条件的类：
 *              1. public声明，可继承（非final）
 *              2. 在别的类中被实例化且被储存为field声明的对象
 *              3. 实例化后某方法被调用
 * @kind problem
 * @problem.severity recommendation
 * @id java/fixed-inheritable-instantiated-classes
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
      isTargetPackage(ownerClassName)
select ownerClassName.getQualifiedName() as ownerClass, 
       f.getName() as fieldName, 
       fieldType.getQualifiedName() as fieldTypeName, 
       methodCall.getCallee().getName() as calledMethod