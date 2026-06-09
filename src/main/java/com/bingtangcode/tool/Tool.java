package com.bingtangcode.tool;

import java.util.Map;

/**
 * 每个工具实现此接口。
 *
 * 四个方法分两类：
 *   元信息（给 LLM 看）—— getName / getDescription / getParametersSchema
 *     各 LLMProvider 直接取出填入 API 请求，告诉模型"有哪些工具、什么参数"
 *   执行（真正干活）—— execute(params)
 *     由 ToolExecutor 调用，模型填入的 JSON 参数被解析为 Map 后传入
 */
public interface Tool {

    /** 工具唯一名称（如 read_file），用于 API 工具定义和 ToolRegistry 注册 */
    String getName();

    /** 一句话描述工具用途，模型据此判断是否调用 */
    String getDescription();

    /** 参数约束，JSON Schema 字符串，与 Anthropic input_schema / OpenAI parameters 字段对齐 */
    String getParametersSchema();

    /** 执行工具，params 为模型填的参数。返回 ToolResult（成功或失败），由 ToolExecutor 包装超时/异常 */
    ToolResult execute(Map<String, Object> params);
}
