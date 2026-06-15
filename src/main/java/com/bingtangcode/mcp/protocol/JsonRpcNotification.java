package com.bingtangcode.mcp.protocol;

public class JsonRpcNotification {
    private String jsonrpc = "2.0";
    private String method;
    private Object params;

    public JsonRpcNotification() {}

    public JsonRpcNotification(String method, Object params) {
        this.method = method;
        this.params = params;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }
}
