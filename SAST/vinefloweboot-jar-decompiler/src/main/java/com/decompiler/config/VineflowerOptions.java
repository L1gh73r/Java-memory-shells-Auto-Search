package com.decompiler.config;

import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.util.HashMap;
import java.util.Map;

public class VineflowerOptions {

    public static Map<String, Object> getDefault() {
        Map<String, Object> options = new HashMap<>();

        options.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");                 // 移除桥接方法
        options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");              // 移除合成成员
        options.put(IFernflowerPreferences.DECOMPILE_INNER, "1");               // 反编译内部类
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");  // 反编译泛型
        options.put(IFernflowerPreferences.ASCII_STRING_CHARACTERS, "1");       // ASCII 字符串
        options.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");      // 包含整个 classpath

        options.put(IFernflowerPreferences.INDENT_STRING, "    ");              // 缩进（4 个空格）

        return options;
    }


}