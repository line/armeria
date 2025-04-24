package com.linecorp.armeria.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcResponse {

    private final String jsonrpc;
    @Nullable
    private final Object result;
    @Nullable
    private final JsonRpcError error;
    @Nullable
    private final Object id;

    private JsonRpcResponse(@Nullable Object result, @Nullable Object id) {
        this.jsonrpc = "2.0"; // Fixed value for JSON-RPC version
        this.result = result;
        this.error = null;
        this.id = id;
    }

    private JsonRpcResponse(JsonRpcError error, @Nullable Object id) {
        this.jsonrpc = "2.0"; // Fixed value for JSON-RPC version
        this.result = null;
        this.error = error;
        this.id = id;
    }

    @JsonCreator
    public JsonRpcResponse(@JsonProperty("jsonrpc") String jsonrpc, // Consume the field, even if fixed
                           @JsonProperty("result") @Nullable Object result,
                           @JsonProperty("error") @Nullable JsonRpcError error,
                           @JsonProperty("id") @Nullable Object id) {
        this.jsonrpc = jsonrpc;
        this.result = result;
        this.error = error;
        this.id = id;
    }

    public static JsonRpcResponse ofSuccess(@Nullable Object result, @Nullable Object id) {
        return new JsonRpcResponse(result, id);
    }

    public static JsonRpcResponse ofError(JsonRpcError error, @Nullable Object id) {
        return new JsonRpcResponse(error, id);
    }

    @JsonProperty
    public String jsonrpc() {
        return jsonrpc;
    }

    @JsonProperty
    @Nullable
    public Object result() {
        return result;
    }

    @JsonProperty
    @Nullable
    public JsonRpcError error() {
        return error;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS) // Ensure 'id' is always present, even if null
    @JsonProperty
    @Nullable
    public Object id() {
        return id;
    }
} 