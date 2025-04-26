package com.linecorp.armeria.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linecorp.armeria.common.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcError {

    private final int code;
    private final String message;
    @Nullable
    private final Object data;

    public JsonRpcError(JsonRpcErrorCode code, @Nullable Object data) {
        this(code.code(), code.message(), data);
    }

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
        return new JsonRpcError(JsonRpcErrorCode.PARSE_ERROR, data);
    }

    public static JsonRpcError invalidRequest(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INVALID_REQUEST, data);
    }

     public static JsonRpcError methodNotFound(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.METHOD_NOT_FOUND, data);
    }

     public static JsonRpcError invalidParams(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, data);
    }

     public static JsonRpcError internalError(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR, data);
    }
} 