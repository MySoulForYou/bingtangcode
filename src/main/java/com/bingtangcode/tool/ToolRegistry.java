package com.bingtangcode.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心，启动时手动注册工具实例，按名查找。
 * 不做 API 格式转换——那是各 LLMProvider 适配层的职责。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tools.containsKey(tool.getName())) {
            throw new IllegalStateException("工具名重复: " + tool.getName());
        }
        tools.put(tool.getName(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** 返回不可变副本，防止调用方绕过 register() 直接修改内部状态 */
    public List<Tool> getAll() {
        return List.copyOf(tools.values());
    }
}
