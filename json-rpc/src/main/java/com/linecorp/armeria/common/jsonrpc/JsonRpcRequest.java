package com.linecorp.armeria.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcRequest {

    private final String jsonrpc;
    private final String method;
    @Nullable
    private final JsonNode params;
    @Nullable
    private final Object id;

    @JsonCreator
    public JsonRpcRequest(@JsonProperty(value = "jsonrpc", required = true) String jsonrpc,
                          @JsonProperty(value = "method", required = true) String method,
                          @JsonProperty("params") @Nullable JsonNode params,
                          @JsonProperty("id") @Nullable Object id) {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("Invalid jsonrpc version: " + jsonrpc);
        }
        this.jsonrpc = jsonrpc;
        this.method = method;
        this.params = params;
        this.id = id;
    }

    @JsonProperty
    public String jsonrpc() {
        return jsonrpc;
    }

    @JsonProperty
    public String method() {
        return method;
    }

    @JsonProperty
    @Nullable
    public JsonNode params() {
        return params;
    }

    @JsonProperty
    @Nullable
    public Object id() {
        return id;
    }

    public boolean isNotification() {
        return id == null;
    }

    public boolean hasArrayParams() {
        return params != null && params.isArray();
    }

    public boolean hasObjectParams() {
        return params != null && params.isObject();
    }
} 