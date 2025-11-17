package com.dynamictracer;

import com.dynamictracer.core.DefaultTransformer;
//import com.dynamictracer.core.ProxyMiddlewareTransformer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.URL;
import java.util.jar.JarFile;
import java.net.URLDecoder;

public class Agent {

    // 全局唯一的DefaultTransformer实例
    private static DefaultTransformer globalTransformer;

    public static void premain(String agentArgs, Instrumentation inst) throws IOException {
        System.out.println("Agent loaded with arguments: " + agentArgs);
        addJarToBootstrap(inst);
        
        // 设置全局instrumentation引用
        setGlobalInstrumentation(inst);
        
        // 创建全局唯一的transformer实例
        globalTransformer = new DefaultTransformer();
        inst.addTransformer(globalTransformer);
//        inst.addTransformer(new ProxyMiddlewareTransformer());
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        System.out.println("Agent dynamically loaded with arguments: " + agentArgs);
        addJarToBootstrap(inst);
        
        // 设置全局instrumentation引用，供后续使用
        setGlobalInstrumentation(inst);
        
        // 如果还没有创建transformer，则创建一个
        if (globalTransformer == null) {
            globalTransformer = new DefaultTransformer();
        }
        inst.addTransformer(globalTransformer, true);
        
        for (Class clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().startsWith("javax.servlet.http.HttpServlet") || clazz.getName().startsWith("org.apache.catalina.core.ApplicationFilterChain")) {
                inst.retransformClasses(clazz);
            }
        }
    }
    
    private static Instrumentation globalInst;
    
    public static void setGlobalInstrumentation(Instrumentation inst) {
        globalInst = inst;
    }
    
    public static Instrumentation getGlobalInstrumentation() {
        return globalInst;
    }

    public static void addJarToBootstrap(Instrumentation inst) throws IOException {
        String localJarPath = getLocalJarPath();
        inst.appendToBootstrapClassLoaderSearch(new JarFile(localJarPath));
    }

    /**
     * 获取当前所在jar包的路径
     *
     * @return jar包路径
     */
    public static String getLocalJarPath() {
        URL localUrl = Agent.class.getProtectionDomain().getCodeSource().getLocation();
        String path = null;
        try {
            path = URLDecoder.decode(
                    localUrl.getFile().replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            System.err.println("[OpenRASP] Failed to get jarFile path.");
            e.printStackTrace();
        }
        return path;
    }
}
