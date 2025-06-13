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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents a JSON-RPC 2.0 error object, which is included in a response when an error occurs.
 * An error object has three members:
 * <ul>
 *   <li>{@code code}: A Number that indicates the error type that occurred. This MUST be an integer.</li>
 *   <li>{@code message}: A String providing a short description of the error.</li>
 *   <li>{@code data}: A Primitive or Structured value that contains additional information about the error.
 *       This may be omitted.</li>
 * </ul>
 * This class is designed to be easily serialized to and deserialized from JSON using Jackson annotations.
 *
 * @see <a href="https://www.jsonrpc.org/specification#error_object">
 *     JSON-RPC 2.0 Specification - Error object</a>
 */
@UnstableApi
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcError {

    /**
     * Invalid Request (-32600).
     * The JSON sent is not a valid Request object.
     */
    public static final JsonRpcError INVALID_REQUEST = new JsonRpcError(-32600, "Invalid Request");

    /**
     * Method not found (-32601).
     * The method does not exist / is not available.
     */
    public static final JsonRpcError METHOD_NOT_FOUND = new JsonRpcError(-32601, "Method not found");

    /**
     * Invalid params (-32602).
     * Invalid method parameter(s).
     */
    public static final JsonRpcError INVALID_PARAMS = new JsonRpcError(-32602, "Invalid params");

    /**
     * Internal error (-32603).
     * Internal JSON-RPC error.
     */
    public static final JsonRpcError INTERNAL_ERROR = new JsonRpcError(-32603, "Internal error");

    /**
     * Parse error (-32700).
     * Invalid JSON was received by the server.
     * An error occurred on the server while parsing the JSON text.
     */
    public static final JsonRpcError PARSE_ERROR = new JsonRpcError(-32700, "Parse error");

    private final int code;
    private final String message;
    @Nullable
    private final Object data;

    /**
     * Creates a new instance with the specified code, message, and optional data.
     * This constructor is annotated with {@link JsonCreator} and {@link JsonProperty} to be used by
     * Jackson for deserializing a JSON error object into a {@link JsonRpcError} instance.
     *
     * @param code    the integer error code.
     * @param message a string providing a short description of the error. Must not be {@code null}.
     * @param data    optional, application-defined data providing additional information about the error.
     *                Can be {@code null}.
     */
    @JsonCreator
    public JsonRpcError(@JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") @Nullable Object data) {
        this.code = code;
        this.message = requireNonNull(message, "message");
        this.data = data;
    }

    /**
     * Creates a new instance with the specified code and message, and no additional data.
     * This is a convenience constructor for creating an error object when no specific {@code data} is needed.
     *
     * @param code    the integer error code.
     * @param message a string providing a short description of the error. Must not be {@code null}.
     */
    public JsonRpcError(int code, String message) {
        this(code, requireNonNull(message, "message"), null);
    }

    /**
     * Creates a new {@link JsonRpcError} instance with the same code and message as this instance,
     * but with the specified {@code data}. If the provided {@code data} is {@code null},
     * this method returns the current instance.
     *
     * @param data the new data to associate with the error. May be {@code null}.
     * @return the current {@link JsonRpcError} instance if {@code data} is {@code null},
     *         otherwise a new {@link JsonRpcError} instance with the updated data.
     */
    public JsonRpcError withData(@Nullable Object data) {
        if (data == null) {
            return this;
        }

        return new JsonRpcError(this.code, this.message, data);
    }

    /**
     * Returns the integer error code that indicates the error type that occurred.
     *
     * @return the error code.
     */
    @JsonProperty
    public int code() {
        return code;
    }

    /**
     * Returns the string providing a short description of the error.
     *
     * @return the error message.
     */
    @JsonProperty
    public String message() {
        return message;
    }

    /**
     * Returns the optional, application-defined data that provides additional information about the error.
     * This can be a primitive or a structured value.
     *
     * @return the additional error data, or {@code null} if not present.
     */
    @JsonProperty
    @Nullable
    public Object data() {
        return data;
    }
}
