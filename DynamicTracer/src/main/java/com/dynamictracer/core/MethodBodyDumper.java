package com.dynamictracer.core;

import javassist.CtMethod;

import java.io.FileWriter;
import java.io.IOException;

/**
 * 简单的方法体输出工具
 */
public class MethodBodyDumper {

    /**
     * 将方法体输出到文件
     *
     * @param method 要输出的方法
     * @param fileName 输出文件名
     */
    public static void dumpMethodBody(CtMethod method, String fileName) {
        try {
            // 获取方法体
            String body = null;
            try {
                body = method.getMethodInfo().getCodeAttribute().toString();
            } catch (Exception e) {
                body = "无法获取方法体: " + e.getMessage();
            }

            // 完整的文件路径
            String filePath = "/tmp/" + fileName;

            // 写入文件
            FileWriter writer = new FileWriter(filePath);
            writer.write("类名: " + method.getDeclaringClass().getName() + "\n");
            writer.write("方法名: " + method.getName() + "\n");
            writer.write("方法签名: " + method.getSignature() + "\n\n");
            writer.write("方法体:\n");
            writer.write(body + "\n");
            writer.close();

            System.out.println("方法体已输出到文件: " + filePath);
        } catch (IOException e) {
            System.out.println("输出方法体时出错: " + e.getMessage());
        }
    }

    /**
     * 记录代码字符串到文件
     *
     * @param code 要记录的代码
     * @param fileName 文件名
     */
    public static void dumpCode(String code, String fileName) {
        try {
            String filePath = "/tmp/" + fileName;
            FileWriter writer = new FileWriter(filePath);
            writer.write(code);
            writer.close();
            System.out.println("代码已输出到文件: " + filePath);
        } catch (IOException e) {
            System.out.println("输出代码时出错: " + e.getMessage());
        }
    }
}