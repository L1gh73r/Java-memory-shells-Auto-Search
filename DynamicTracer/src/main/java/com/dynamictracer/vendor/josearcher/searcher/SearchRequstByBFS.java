package com.dynamictracer.vendor.josearcher.searcher;

import com.dynamictracer.vendor.josearcher.entity.Blacklist;
import com.dynamictracer.vendor.josearcher.entity.Keyword;
import com.dynamictracer.vendor.josearcher.entity.NodeT;
import com.dynamictracer.vendor.josearcher.utils.CheckUtil;
import com.dynamictracer.vendor.josearcher.utils.LogUtil;
import com.dynamictracer.vendor.josearcher.utils.MatchUtil;
import java.lang.reflect.Field;
import java.util.*;
import static com.dynamictracer.vendor.josearcher.utils.CommonUtil.*;
import static com.dynamictracer.vendor.josearcher.utils.CommonUtil.write2log;
import static com.dynamictracer.vendor.josearcher.utils.CheckUtil.isList;
import static com.dynamictracer.vendor.josearcher.utils.CheckUtil.isMap;

public class SearchRequstByBFS {
    //private Logger logger = Logger.getLogger(SearchRequstByBFS.class);
    private String model_name = SearchRequstByBFS.class.getSimpleName();
    private Object target;
    private List<Keyword> keys = new ArrayList<>();
    private List<Blacklist> blacklists = new ArrayList<>();
    private int max_search_depth = Integer.MAX_VALUE;/* 递归搜索深度 */
    private boolean is_debug = true;
    private boolean include_stacktrace = false;
    private int stacktrace_depth = 10;
    private boolean use_timestamp = true;
    private String output_format = "TEXT"; // TEXT or JSON
    private String output_basename = null; // without suffix
    private String result_file_override;
    private String all_chain_file_override;
    private String err_log_file_override;
    private String search_tag;
    //记录所有访问过的元素（基于引用标识，避免equals误判）
    private Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    //用队列存放所有依次要访问元素
    private Queue<NodeT> q = new LinkedList<NodeT>();
    //把当前的元素加入到队列尾
    private String report_save_path = null;
    private String result_file;
    private String all_chain_file;
    private String err_log_file;


    public SearchRequstByBFS(Object target, List<Keyword> keys){
        this.target = target;
        this.keys = keys;
        //把当前的元素加入到队列尾
        q.offer(new NodeT.Builder().setChain("").setField_name("TargetObject").setField_object(target).build());
    }

    public void initSavePath(){
        if (result_file_override != null || all_chain_file_override != null || err_log_file_override != null) {
            if (result_file_override != null) this.result_file = result_file_override;
            if (all_chain_file_override != null) this.all_chain_file = all_chain_file_override;
            if (err_log_file_override != null) this.err_log_file = err_log_file_override;
            return;
        }
        String baseDir = report_save_path;
        String baseName = output_basename != null ? output_basename : model_name;
        String suffix = use_timestamp ? ("_" + getCurrentDate()) : "";
        String resultExt = "JSON".equalsIgnoreCase(output_format) ? ".jsonl" : ".log";
        String logExt = ".log";
        if (baseDir == null) {
            this.result_file = String.format("%s_result%s%s", baseName, suffix, resultExt);
            this.all_chain_file = String.format("%s_log%s%s", baseName, suffix, logExt);
            this.err_log_file = String.format("%s_error%s%s", baseName, suffix, logExt);
        } else {
            this.result_file = String.format("%s/%s_result%s%s", baseDir, baseName, suffix, resultExt);
            this.all_chain_file = String.format("%s/%s_log%s%s", baseDir, baseName, suffix, logExt);
            this.err_log_file = String.format("%s/%s_error%s%s", baseDir, baseName, suffix, logExt);
        }
    }


    public void setBlacklists(List<Blacklist> blacklists) {
        this.blacklists = blacklists;
    }

    public void setMax_search_depth(int max_search_depth) {
        this.max_search_depth = max_search_depth;
    }

    public void setReport_save_path(String report_save_path) {
        this.report_save_path = report_save_path;
    }

    public void setIs_debug(boolean is_debug) {
        this.is_debug = is_debug;
    }

    public void setInclude_stacktrace(boolean include) {
        this.include_stacktrace = include;
    }

    public void setStacktrace_depth(int depth) {
        this.stacktrace_depth = depth > 0 ? depth : 10;
    }

    // 新增可配置项
    public void setUse_timestamp(boolean use) { this.use_timestamp = use; }
    public void setOutput_format(String format) { this.output_format = format != null ? format.toUpperCase() : "TEXT"; }
    public void setOutput_basename(String name) { this.output_basename = name; }
    public void setResult_file(String file) { this.result_file_override = file; }
    public void setAll_chain_file(String file) { this.all_chain_file_override = file; }
    public void setErr_log_file(String file) { this.err_log_file_override = file; }
    public void setSearch_tag(String tag) { this.search_tag = tag; }

    public void setErrLogFile(String err_log_file) {
        this.err_log_file = err_log_file;
    }

    // Getters for result paths
    public String getResult_file() {
        return this.result_file;
    }

    public String getAll_chain_file() {
        return this.all_chain_file;
    }

    public String getErr_log_file() {
        return this.err_log_file;
    }

    public void searchObject(){
        this.initSavePath();
        int writeCount = 0;
        while(!q.isEmpty()){
            NodeT node = q.poll();
            String filed_name = node.getField_name();
            Object filed_object = node.getField_object();
            String log_chain = node.getChain();
            String new_log_chain = null;
            int current_depth = node.getCurrent_depth();

            //最多挖多深
            if(current_depth > max_search_depth){
                continue;
            }

            if (filed_object == null || CheckUtil.isSysType(filed_object) || MatchUtil.isInBlacklist(filed_name,filed_object,this.blacklists)){
                //如果object是null/基本数据类型/包装类/日期类型，则不需要在递归调用
                continue;
            }

            try {
                //被访问过了，就不访问，防止死循环
                // 注意：Set.contains 可能存在空指针异常
                if (!visited.contains(filed_object)) {
                    visited.add(filed_object);

                    if (log_chain != null && log_chain != "") {
                        current_depth++;
                        new_log_chain = String.format("%s \n%s ---> %s = {%s}", log_chain, getBlank(current_depth), filed_name, filed_object.getClass().getName());
                    } else {
                        new_log_chain = String.format("%s = {%s}", "TargetObject", filed_object.getClass().getName());
                    }

                    // 搜索操作
                    if (MatchUtil.matchObject(filed_name, filed_object, keys)) {
                        if ("JSON".equalsIgnoreCase(output_format)) {
                            write2log(result_file, buildJsonLine(filed_name, filed_object, new_log_chain, current_depth));
                            writeCount++;
                        } else {
                            write2log(result_file, new_log_chain + "\n\n\n");
                            if (include_stacktrace) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("STACKTRACE:\n");
                                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                int end = Math.min(st.length, stacktrace_depth);
                                for (int i = 0; i < end; i++) {
                                    sb.append("  at ").append(st[i].toString()).append("\n");
                                }
                                sb.append("\n");
                                write2log(result_file, sb.toString());
                            }
                        }
                        if (writeCount >= 10) {
                            // 达到上限，停止进一步写入
                            return;
                        }
                    }
                    if (is_debug) {
                        write2log(all_chain_file, new_log_chain + "\n\n\n");
                    }
                    // 控制台不再大量打印，避免干扰

                    // 添加新节点到队列
                    Class clazz = filed_object.getClass();
                    if (filed_object instanceof List) {

                    }


                    if (filed_object instanceof Map) {
                        current_depth++;
                        Map map = (Map) filed_object;
                        if (map != null && map.size() > 0) {
                            Iterator iterator = map.values().iterator();
                            while (iterator.hasNext()) {
                                NodeT n = new NodeT.Builder().setField_name(filed_name).setField_object(iterator.next()).setChain(new_log_chain).setCurrent_depth(current_depth).build();
                                q.offer(n);
                            }
                        }
                    }

                    if (clazz.isArray()) {
                        current_depth++;
                        try {
                            //Object[] 没有属性
                            Object[] obj_arr = (Object[]) filed_object;
                            if (obj_arr != null && obj_arr.length > 0) {
                                for (int i = 0; i < obj_arr.length; i++) {
                                    if (obj_arr[i] == null) {
                                        continue;
                                    }
                                    String arr_type = obj_arr[i].getClass().getName();
                                    String arr_name = String.format("[%d] = {%s}", i, arr_type);
                                    NodeT n = new NodeT.Builder().setField_name(arr_name).setField_object(obj_arr[i]).setChain(new_log_chain).setCurrent_depth(current_depth).build();
                                    q.offer(n);
                                }
                            }
                        } catch (Throwable e) {
                            //logger.error(String.format("%s - %s",model_name,"clazz.isArray"),e);
                            LogUtil.saveThrowableInfo(e, this.err_log_file);
                        }
                    }


                    for (; clazz != Object.class; clazz = clazz.getSuperclass()) {//向上循环 遍历父类
                        Field[] fields = clazz.getDeclaredFields();

                        for (Field field : fields) {
                            field.setAccessible(true);
                            String proType = field.getGenericType().toString();
                            String proName = field.getName();
                            Object subObj = null;
                            try {
                                subObj = field.get(filed_object);
                            } catch (Throwable e) {
                                //logger.error(String.format("%s - %s",model_name,"class"),e);
                                LogUtil.saveThrowableInfo(e, this.err_log_file);
                            }

                            if (subObj == null) {
                                continue;
                            } else if (CheckUtil.isSysType(field)) {
                                //属性是系统类型跳过
                                continue;
                            } else if (MatchUtil.isInBlacklist(proName, subObj, this.blacklists)) {
                                continue;
                            } else if (isList(field)) {
                                //对List,ArrayList类型的属性遍历
                                try {
                                    List list = (List) field.get(filed_object);
                                    if (list != null && list.size() > 0) {
                                        current_depth++;
                                        String tmp_log_chain = String.format("%s \n%s ---> %s = {%s}", new_log_chain, getBlank(current_depth), proName, proType);
                                        int len = list.size();
                                        for (int i = 0; i < len; i++) {
                                            if (list.get(i) == null) {
                                                continue;
                                            }
                                            String list_name = String.format("[%d]", i);
                                            NodeT n = new NodeT.Builder().setField_name(list_name).setField_object(list.get(i)).setChain(tmp_log_chain).setCurrent_depth(current_depth).build();
                                            q.offer(n);
                                        }
                                    }
                                } catch (Throwable e) {
                                    //logger.error(String.format("%s - %s",model_name,"isList"),e);
                                    LogUtil.saveThrowableInfo(e, this.err_log_file);
                                }
                            } else if (isMap(field)) {
                                try {
                                    //对Map,HashMap类型的属性遍历
                                    Map map = (Map) field.get(filed_object);
                                    if (map != null && map.size() > 0) {
                                        current_depth++;
                                        String tmp_log_chain = String.format("%s \n%s ---> %s = {%s}", new_log_chain, getBlank(current_depth), proName, proType);

                                        Iterator<String> iter = map.keySet().iterator();
                                        while (iter.hasNext()) {
                                            Object key = iter.next();
                                            Object value = map.get(key);
                                            String map_name = String.format("[%s]", key.toString());
                                            NodeT n = new NodeT.Builder().setField_name(map_name).setField_object(value).setChain(tmp_log_chain).setCurrent_depth(current_depth).build();
                                            q.offer(n);
                                        }
                                    }

                                } catch (Throwable e) {
                                    //logger.error(String.format("%s - %s",model_name,"isMap"),e);
                                    LogUtil.saveThrowableInfo(e, this.err_log_file);
                                }
                            } else if (field.getType().isArray()) {
                                try {
                                    //属性是数组类型则遍历
                                    Object obj = field.get(filed_object);
                                    if (obj == null) {
                                        continue;
                                    }
                                    current_depth++;
                                    Object[] objArr = (Object[]) obj;
                                    if (objArr != null && objArr.length > 0) {

                                        for (int i = 0; i < objArr.length; i++) {
                                            if (objArr[i] == null) {
                                                continue;
                                            }

                                            String tmp_log_chain = String.format("%s \n%s ---> %s = {%s}", new_log_chain, getBlank(current_depth), proName, proType);
                                            String arr_name = String.format("[%d]", i);
                                            NodeT n = new NodeT.Builder().setField_name(arr_name).setField_object(objArr[i]).setChain(tmp_log_chain).setCurrent_depth(current_depth).build();
                                            q.offer(n);
                                        }
                                    }
                                } catch (Throwable e) {
                                    //logger.error(String.format("%s - %s",model_name,"isArray"),e);
                                    LogUtil.saveThrowableInfo(e, this.err_log_file);
                                }
                            } else {
                                try {
                                    //class类型的遍历
                                    NodeT n = new NodeT.Builder().setField_name(proName).setField_object(subObj).setChain(new_log_chain).setCurrent_depth(current_depth).build();
                                    q.offer(n);
                                } catch (Throwable e) {
                                    //logger.error(String.format("%s - %s",model_name,"class"),e);
                                    LogUtil.saveThrowableInfo(e, this.err_log_file);
                                }
                            }
                        }
                    }
                }
            }catch (Throwable e){
                LogUtil.saveThrowableInfo(e,this.err_log_file);
            }
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // 构造一行 JSON（jsonl），便于后续离线分析
    private String buildJsonLine(String fieldName, Object value, String chain, int depth) {
        String cls = value != null ? value.getClass().getName() : "null";
        String tag = this.search_tag != null ? this.search_tag : this.model_name;
        String json = "{" +
                "\"tag\":\"" + escapeJson(tag) + "\"," +
                "\"field_name\":\"" + escapeJson(fieldName) + "\"," +
                "\"class\":\"" + escapeJson(cls) + "\"," +
                "\"depth\":" + depth + "," +
                "\"chain\":\"" + escapeJson(chain) + "\"" +
                "}";
        return json + "\n";
    }
}

