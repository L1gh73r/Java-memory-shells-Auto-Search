public class TestGeneratedCode {
    public static void main(String[] args) {
        // 测试生成的josearcher代码
        String code = "System.out.println(\"🔍 开始josearcher搜索...\"); " +
                   "try { " +
                   "  ClassLoader cl = Thread.currentThread().getContextClassLoader(); " +
                   "  if (cl == null) cl = ClassLoader.getSystemClassLoader(); " +
                   "  " +
                   "  // 尝试不同的类名组合来适配不同版本的josearcher " +
                   "  Class keywordClass = null; " +
                   "  Class builderClass = null; " +
                   "  try { " +
                   "    keywordClass = cl.loadClass(\"me.gv7.tools.josearcher.entity.Keyword\"); " +
                   "    try { " +
                   "      builderClass = cl.loadClass(\"me.gv7.tools.josearcher.entity.Keyword$Builder\"); " +
                   "    } catch (ClassNotFoundException e) { " +
                   "      // 尝试内部类的另一种命名方式 " +
                   "      try { " +
                   "        builderClass = keywordClass.getDeclaredClasses()[0]; " +
                   "      } catch (Exception ex) { " +
                   "        System.err.println(\"❌ 无法找到Keyword.Builder类\"); " +
                   "        throw new RuntimeException(\"Builder类未找到\"); " +
                   "      } " +
                   "    } " +
                   "  } catch (ClassNotFoundException e) { " +
                   "    System.err.println(\"❌ josearcher Keyword类未找到: \" + e.getMessage()); " +
                   "    throw new RuntimeException(\"Keyword类未找到\"); " +
                   "  } " +
                   "  " +
                   "  Class searcherClass = cl.loadClass(\"me.gv7.tools.josearcher.searcher.SearchRequstByBFS\"); " +
                   "  " +
                   "  System.out.println(\"✅ josearcher类加载成功\"); " +
                   "  System.out.println(\"   关键词类: \" + keywordClass.getName()); " +
                   "  System.out.println(\"   构建器类: \" + builderClass.getName()); " +
                   "  System.out.println(\"   搜索器类: \" + searcherClass.getName()); " +
                   "  " +
                   "  // 简化实现: 仅创建搜索器并执行基础搜索 " +
                   "  try { " +
                   "    java.util.List keys = new java.util.ArrayList(); " +
                   "    java.lang.reflect.Constructor searcherConstructor = searcherClass.getConstructor(new Class[]{Object.class, java.util.List.class}); " +
                   "    Object searcher = searcherConstructor.newInstance(new Object[]{Thread.currentThread(), keys}); " +
                   "    " +
                   "    java.lang.reflect.Method setDebug = searcherClass.getMethod(\"setIs_debug\", new Class[]{boolean.class}); " +
                   "    setDebug.invoke(searcher, new Object[]{Boolean.TRUE}); " +
                   "    " +
                   "    java.lang.reflect.Method searchObject = searcherClass.getMethod(\"searchObject\", new Class[0]); " +
                   "    searchObject.invoke(searcher, new Object[0]); " +
                   "    " +
                   "    System.out.println(\"✅ josearcher搜索执行完成\"); " +
                   "  } catch (Exception e) { " +
                   "    System.err.println(\"❌ josearcher搜索执行失败: \" + e.getMessage()); " +
                   "    e.printStackTrace(); " +
                   "  } " +
                   "} catch (ClassNotFoundException e) { " +
                   "  System.err.println(\"❌ josearcher库未找到: \" + e.getMessage()); " +
                   "} catch (Exception e) { " +
                   "  System.err.println(\"❌ josearcher初始化失败: \" + e.getMessage()); " +
                   "  e.printStackTrace(); " +
                   "}";
        
        System.out.println("Generated code:");
        System.out.println(code);
        
        // 检查括号平衡
        int openBraces = 0;
        int closeBraces = 0;
        for (char c : code.toCharArray()) {
            if (c == '{') openBraces++;
            if (c == '}') closeBraces++;
        }
        System.out.println("Open braces: " + openBraces);
        System.out.println("Close braces: " + closeBraces);
        System.out.println("Balanced: " + (openBraces == closeBraces));
    }
}
