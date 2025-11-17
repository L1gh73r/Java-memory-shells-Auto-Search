// josearcher搜索代码示例
// 这个文件可以被断点配置引用，避免在JSON中存储复杂代码

try {
    System.out.println("🔍 开始josearcher搜索...");
    
    // 设置搜索类型包含Request关键字的对象
    List<me.gv7.tools.josearcher.entity.Keyword> keys = new ArrayList<>();
    keys.add(new me.gv7.tools.josearcher.entity.Keyword.Builder().setField_type("Request").build());
    
    // 定义黑名单
    List<me.gv7.tools.josearcher.entity.Blacklist> blacklists = new ArrayList<>();
    blacklists.add(new me.gv7.tools.josearcher.entity.Blacklist.Builder().setField_type("java.io.File").build());
    
    // 新建一个广度优先搜索Thread.currentThread()的搜索器
    me.gv7.tools.josearcher.searcher.SearchRequstByBFS searcher = 
        new me.gv7.tools.josearcher.searcher.SearchRequstByBFS(Thread.currentThread(), keys);
    
    // 设置黑名单
    searcher.setBlacklists(blacklists);
    
    // 打开调试模式，会生成log日志
    searcher.setIs_debug(true);
    
    // 挖掘深度为20
    searcher.setMax_search_depth(20);
    
    // 设置报告保存位置
    searcher.setReport_save_path("/tmp");
    
    // 执行搜索
    searcher.searchObject();
    
    System.out.println("✅ josearcher搜索完成，报告已保存到 /tmp");
    
} catch (Exception e) {
    System.err.println("❌ josearcher搜索失败: " + e.getMessage());
    e.printStackTrace();
}
