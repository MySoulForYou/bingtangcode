package com.bingtangcode.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public class JsonRpcResponse {
    private String jsonrpc;
    private Object id;
    private JsonNode result;
    private Error error;

    public JsonRpcResponse() {}

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public JsonNode getResult() {
        return result;
    }

    public void setResult(JsonNode result) {
        this.result = result;
    }

    public Error getError() {
        return error;
    }

    public void setError(Error error) {
        this.error = error;
    }

    public static class Error {
        private int code;
        private String message;
        private JsonNode data;

        public Error() {}

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public JsonNode getData() {
            return data;
        }

        public void setData(JsonNode data) {
            this.data = data;
        }
    }
}
