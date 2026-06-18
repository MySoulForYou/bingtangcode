package com.bingtangcode.mcp;

import com.bingtangcode.tool.Tool;
import com.bingtangcode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class McpToolAdapter implements Tool {
    private final String serverName;
    private final String remoteName;
    private final String fullName;
    private final String description;
    private final String parametersSchema;
    private final boolean readOnly;
    private final McpSession session;
    private final long timeoutSeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    
    // 一次性警告记录器，针对每个工具的非文本内容丢弃进行告警
    private final Map<String, Boolean> warnedNonText = new ConcurrentHashMap<>();

    private McpToolAdapter(String serverName, String remoteName, String fullName, JsonNode toolNode, McpSession session, long timeoutSeconds) {
        this.serverName = serverName;
        this.remoteName = remoteName;
        this.fullName = fullName;
        this.session = session;
        this.timeoutSeconds = timeoutSeconds;

        // 描述处理
        String desc = toolNode.has("description") ? toolNode.get("description").asText() : "";
        if (desc.isBlank()) {
            this.description = "来自 MCP server " + serverName + " 的工具 " + remoteName;
        } else {
            this.description = desc;
        }

        // 参数 Schema 处理
        String schemaStr = "{\"type\":\"object\"}";
        if (toolNode.has("inputSchema")) {
            try {
                schemaStr = mapper.writeValueAsString(toolNode.get("inputSchema"));
            } catch (Exception ignored) {
            }
        }
        this.parametersSchema = schemaStr;

        // 只读属性适配
        boolean ro = false;
        if (toolNode.has("annotations")) {
            JsonNode annotations = toolNode.get("annotations");
            if (annotations.has("readOnlyHint")) {
                ro = annotations.get("readOnlyHint").asBoolean();
            }
        }
        this.readOnly = ro;
    }

    public static McpToolAdapter create(String serverName, JsonNode toolNode, McpSession session) {
        return create(serverName, toolNode, session, 30);
    }

    public static McpToolAdapter create(String serverName, JsonNode toolNode, McpSession session, long timeoutSeconds) {
        String rName = toolNode.get("name").asText();
        String fName = "mcp__" + serverName + "__" + rName;
        if (!fName.matches("^[A-Za-z0-9_-]+$")) {
            System.err.println("[警告] 工具名称 " + fName + " 含有非法字符，已跳过注册");
            return null;
        }
        return new McpToolAdapter(serverName, rName, fName, toolNode, session, timeoutSeconds);
    }

    @Override
    public String getName() {
        return fullName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getParametersSchema() {
        return parametersSchema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            // 工具执行超时限制
            JsonNode result = session.callTool(remoteName, params, timeoutSeconds);
            
            boolean isError = false;
            if (result.has("isError")) {
                isError = result.get("isError").asBoolean();
            }

            StringBuilder textContent = new StringBuilder();
            if (result.has("content")) {
                JsonNode contentNode = result.get("content");
                if (contentNode.isArray()) {
                    for (JsonNode block : contentNode) {
                        String type = block.has("type") ? block.get("type").asText() : "";
                        if ("text".equals(type)) {
                            if (textContent.length() > 0) {
                                textContent.append("\n");
                            }
                            textContent.append(block.get("text").asText());
                        } else {
                            // 非文本数据块静默丢弃，并在首次遇到时输出一次性 System.err 警告
                            if (warnedNonText.putIfAbsent(fullName, Boolean.TRUE) == null) {
                                System.err.println("[警告] 工具 " + fullName + " 包含非文本内容块，已被静默丢弃");
                            }
                        }
                    }
                }
            }

            return new ToolResult(null, textContent.toString(), isError);
        } catch (Exception e) {
            // 调用过程中的协议错误、网络错误、超时等均转成 isError=true 的结构化错误回灌给模型，不阻断程序运行
            return new ToolResult(null, "MCP 工具调用失败: " + e.getMessage(), true);
        }
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }
}
