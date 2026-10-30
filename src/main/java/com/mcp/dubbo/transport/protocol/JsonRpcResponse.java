package com.mcp.dubbo.transport.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class JsonRpcResponse {
    private String jsonrpc = "2.0";
    private Object id;
    private Object result;
    private JsonRpcError error;

    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(id);
        response.setResult(result);
        return response;
    }

    public static JsonRpcResponse successText(Object id, String text) {
        // Standard MCP tool execution result format: {"content":[{"type":"text","text":"..."}]}
        Map<String, Object> textBlock = Collections.singletonMap("type", "text");
        List<Map<String, String>> content = Collections.singletonList(
                Collections.singletonMap("type", "text")
        );
        Map<String, Object> result = Collections.singletonMap("content",
                Collections.singletonList(new ContentItem("text", text))
        );
        return success(id, result);
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(id);
        response.setError(new JsonRpcError(code, message));
        return response;
    }

    public static class ContentItem {
        private String type;
        private String text;

        public ContentItem(String type, String text) {
            this.type = type;
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public String getText() {
            return text;
        }
    }

    public static class JsonRpcError {
        private int code;
        private String message;

        public JsonRpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

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

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public void setError(JsonRpcError error) {
        this.error = error;
    }
}
