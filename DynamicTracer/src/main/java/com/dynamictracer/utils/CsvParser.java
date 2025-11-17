package com.dynamictracer.utils;

import com.dynamictracer.model.MethodHookEntry;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV解析器，用于解析方法hook配置文件
 */
public class CsvParser {

    /**
     * 解析CSV文件并返回方法hook条目列表
     * 
     * @param csvFilePath CSV文件路径
     * @return 方法hook条目列表
     * @throws IOException 读取文件异常
     */
    public static List<MethodHookEntry> parseMethodHookCsv(String csvFilePath) throws IOException {
        List<MethodHookEntry> entries = new ArrayList<>();
        
        try (BufferedReader br = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            boolean isFirstLine = true;
            
            while ((line = br.readLine()) != null) {
                // 跳过标题行
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                
                // 解析CSV行
                MethodHookEntry entry = parseCsvLine(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        
        return entries;
    }

    /**
     * 按类名分组hook条目，便于快速查找
     * 
     * @param entries hook条目列表
     * @return 按类名分组的map
     */
    public static Map<String, List<MethodHookEntry>> groupByClassName(List<MethodHookEntry> entries) {
        Map<String, List<MethodHookEntry>> groupedEntries = new HashMap<>();
        
        for (MethodHookEntry entry : entries) {
            String className = entry.getTargetClassName();
            groupedEntries.computeIfAbsent(className, k -> new ArrayList<>()).add(entry);
        }
        
        return groupedEntries;
    }

    /**
     * 解析单行CSV数据
     * 
     * @param line CSV行
     * @return 解析得到的MethodHookEntry对象
     */
    private static MethodHookEntry parseCsvLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        
        try {
            // 简单的CSV解析，处理带引号的字段
            List<String> fields = parseCsvFields(line);
            
            if (fields.size() >= 4) {
                return new MethodHookEntry(
                    fields.get(0), // ownerClass
                    fields.get(1), // fieldName
                    fields.get(2), // fieldTypeName
                    fields.get(3)  // calledMethod
                );
            }
        } catch (Exception e) {
            System.err.println("解析CSV行时出错: " + line + ", 错误: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * 解析CSV字段，处理引号包围的字段
     * 
     * @param line CSV行
     * @return 字段列表
     */
    private static List<String> parseCsvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }
        
        // 添加最后一个字段
        fields.add(currentField.toString());
        
        return fields;
    }
}
