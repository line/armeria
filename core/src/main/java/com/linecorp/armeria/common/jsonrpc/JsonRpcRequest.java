/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.jsonrpc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents a JSON-RPC 2.0 request object.
 * A request object has the following members:
 * <ul>
 *   <li>{@code jsonrpc}: A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0".</li>
 *   <li>{@code method}: A String containing the name of the method to be invoked.</li>
 *   <li>{@code params}: A Structured value that holds the parameter values to be used during the invocation
 *       of the method. This member MAY be omitted. It can be an Array (for positional parameters)
 *       or an Object (for named parameters).</li>
 *   <li>{@code id}: An identifier established by the Client. If it is not included, the request is assumed
 *       to be a notification. The value can be a String, a Number, or NULL.</li>
 * </ul>
 * This class is designed for easy serialization to and deserialization from JSON using Jackson annotations.
 *
 * @see <a href="https://www.jsonrpc.org/specification#request_object">
 *     JSON-RPC 2.0 Specification - Request object</a>
 */
@UnstableApi
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcRequest {

    private final String jsonrpc;
    private final String method;
    @Nullable
    private final JsonNode params;
    @Nullable
    private final Object id;

    /**
     * Creates a new JSON-RPC request instance.
     * This constructor is annotated with {@link JsonCreator} and {@link JsonProperty} to be used by
     * Jackson for deserializing a JSON request object into a {@link JsonRpcRequest} instance.
     *
     * @param jsonrpc the JSON-RPC protocol version string. Must be "2.0".
     * @param method  the name of the method to be invoked. Must not be {@code null}.
     * @param params  the parameters for the method, as a {@link JsonNode}. Can be an array for positional
     *                parameters, an object for named parameters, or {@code null} if no parameters are provided.
     * @param id      the request identifier. Can be a {@link String}, a {@link Number}, or {@code null}.
     *                A {@code null} ID (or its absence in JSON) signifies a notification.
     */
    @JsonCreator
    public JsonRpcRequest(@JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("method") String method,
            @JsonProperty("params") @Nullable JsonNode params,
            @JsonProperty("id") @Nullable Object id) {
        checkArgument(JsonRpcUtil.JSON_RPC_VERSION.equals(jsonrpc), "jsonrpc must be '2.0', but was: %s",
                jsonrpc);
        this.jsonrpc = jsonrpc;
        this.method = requireNonNull(method, "method");
        this.params = params;
        this.id = id;
    }

    /**
     * Returns the JSON-RPC protocol version string.
     * According to the JSON-RPC 2.0 specification, this MUST be exactly "2.0".
     *
     * @return the JSON-RPC version string.
     */
    @JsonProperty(value = "jsonrpc")
    public String jsonRpcVersion() {
        return jsonrpc;
    }

    /**
     * Returns the name of the method to be invoked.
     *
     * @return the method name.
     */
    @JsonProperty
    public String method() {
        return method;
    }

    /**
     * Returns the parameters for the method invocation, if any.
     * The parameters are represented as a {@link JsonNode}, which can be a JSON Array
     * (for positional parameters)
     * or a JSON Object (for named parameters).
     *
     * @return the {@link JsonNode} containing the parameters, or {@code null} if parameters are omitted.
     */
    @JsonProperty
    @Nullable
    public JsonNode params() {
        return params;
    }

    /**
     * Returns the request identifier (ID).
     * The ID is established by the client and can be a {@link String}, a {@link Number}, or {@code null}.
     * If the ID is {@code null}, this request is considered a notification, and no response is expected.
     *
     * @return the request ID, or {@code null} if this is a notification or if the ID was not provided.
     */
    @JsonProperty
    @Nullable
    public Object id() {
        return id;
    }

    /**
     * Returns {@code true} if this request is a notification.
     * A request is a notification if its {@code id} member is {@code null} (or was not included
     * in the original JSON request, which Jackson typically deserializes as {@code null} for this field).
     *
     * @return {@code true} if this request is a notification, {@code false} otherwise.
     */
    public boolean notificationRequest() {
        return id == null;
    }

    /**
     * Returns {@code true} if the parameters ({@link #params()}) exist and are structured as a JSON Array
     * (i.e., positional parameters).
     *
     * @return {@code true} if parameters are present and are a JSON Array, {@code false} otherwise.
     */
    public boolean hasArrayParams() {
        return params != null && params.isArray();
    }

    /**
     * Returns {@code true} if the parameters ({@link #params()}) exist and are structured as a JSON Object
     * (i.e., named parameters).
     *
     * @return {@code true} if parameters are present and are a JSON Object, {@code false} otherwise.
     */
    public boolean hasObjectParams() {
        return params != null && params.isObject();
    }
}
