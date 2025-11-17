try {
    // 获取当前请求的 WebApplicationContext
    org.springframework.web.context.request.ServletRequestAttributes attr = (org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
    org.springframework.web.context.WebApplicationContext context = org.springframework.web.servlet.support.RequestContextUtils.findWebApplicationContext(attr.getRequest());

    if (context == null) {
        System.out.println("无法获取 WebApplicationContext");
        return;
    }

    // 反射获取 AbstractApplicationContext 中的 applicationEventMulticaster 字段
    java.lang.reflect.Field field = org.springframework.context.support.AbstractApplicationContext.class.getDeclaredField("applicationEventMulticaster");
    field.setAccessible(true);

    // 获取当前的事件广播器
    Object currentMulticaster = field.get(context);
    System.out.println("当前 EventMulticaster 类型: " + currentMulticaster.getClass().getName());

    // 检查是否已被替换，防止重复注入
    if (currentMulticaster.getClass().getName().contains("ExifilSimpleApplicationEventMulticaster")) {
        System.out.println("内存马已存在，无需重复注入");
        return;
    }

    // 注意：由于没有 com.pwn.ExifilSimpleApplicationEventMulticaster 类，这里只做检测
    System.out.println("准备注入内存马到 EventMulticaster");
    System.out.println("目标类: " + currentMulticaster.getClass().getName());
    
    // 如果需要实际替换，需要先创建对应的恶意类
    // 这里只做演示，实际使用时需要准备好恶意类
    
} catch (Exception e) {
    System.err.println("内存马注入失败: " + e.getMessage());
    e.printStackTrace();
}
