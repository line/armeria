package com.linecorp.armeria.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcError {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final int code;
    private final String message;
    @Nullable
    private final Object data;

    @JsonCreator
    public JsonRpcError(@JsonProperty(value = "code", required = true) int code,
                        @JsonProperty(value = "message", required = true) String message,
                        @JsonProperty("data") @Nullable Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    @JsonProperty
    public int code() {
        return code;
    }

    @JsonProperty
    public String message() {
        return message;
    }

    @JsonProperty
    @Nullable
    public Object data() {
        return data;
    }

    public static JsonRpcError parseError(@Nullable Object data) {
        return new JsonRpcError(PARSE_ERROR, "Parse error", data);
    }

    public static JsonRpcError invalidRequest(@Nullable Object data) {
        return new JsonRpcError(INVALID_REQUEST, "Invalid Request", data);
    }

     public static JsonRpcError methodNotFound(@Nullable Object data) {
        return new JsonRpcError(METHOD_NOT_FOUND, "Method not found", data);
    }

     public static JsonRpcError invalidParams(@Nullable Object data) {
        return new JsonRpcError(INVALID_PARAMS, "Invalid params", data);
    }

     public static JsonRpcError internalError(@Nullable Object data) {
        return new JsonRpcError(INTERNAL_ERROR, "Internal error", data);
    }
} 