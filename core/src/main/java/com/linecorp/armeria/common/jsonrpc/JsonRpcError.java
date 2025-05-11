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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.annotation.Nullable;

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
@JsonInclude(JsonInclude.Include.NON_NULL) // Ensure null 'data' field is not included in JSON output
public final class JsonRpcError {

    private final int code;
    private final String message;
    @Nullable
    private final Object data;

    /**
     * Creates a new instance with the specified {@link JsonRpcErrorCode} and optional data.
     * The message associated with the {@link JsonRpcErrorCode} will be used.
     *
     * @param errorCode the predefined {@link JsonRpcErrorCode} indicating the type of error.
     *                  Must not be {@code null}.
     * @param data      optional, application-defined data providing additional information about the error.
     *                  Can be {@code null}.
     */
    public JsonRpcError(JsonRpcErrorCode errorCode, @Nullable Object data) {
        this(errorCode.code(), errorCode.message(), data);
    }

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
    public JsonRpcError(@JsonProperty(value = "code", required = true) int code,
                        @JsonProperty(value = "message", required = true) String message,
                        @JsonProperty("data") @Nullable Object data) {
        this.code = code;
        this.message = message;
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
        this(code, message, null);
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

    /**
     * Creates a 'Parse error' {@link JsonRpcError} (-32700) as defined by the JSON-RPC 2.0 specification.
     * Indicates invalid JSON was received by the server
     * or an error occurred on the server while parsing the JSON text.
     *
     * @param data optional, application-defined data providing additional information about the parse error.
     *             Can be {@code null}.
     * @return a new {@link JsonRpcError} instance representing a parse error.
     */
    public static JsonRpcError parseError(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.PARSE_ERROR, data);
    }

    /**
     * Creates an 'Invalid Request' {@link JsonRpcError} (-32600) as defined by the JSON-RPC 2.0 specification.
     * Indicates the JSON sent is not a valid Request object.
     *
     * @param data optional, application-defined data providing additional information
     *             about why the request was invalid.
     *             Can be {@code null}.
     * @return a new {@link JsonRpcError} instance representing an invalid request error.
     */
    public static JsonRpcError invalidRequest(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INVALID_REQUEST, data);
    }

    /**
     * Creates a 'Method not found' {@link JsonRpcError} (-32601) as defined by the JSON-RPC 2.0 specification.
     * Indicates the method does not exist or is not available.
     *
     * @param data optional, application-defined data. Can be {@code null}.
     * @return a new {@link JsonRpcError} instance representing a method not found error.
     */
    public static JsonRpcError methodNotFound(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.METHOD_NOT_FOUND, data);
    }

    /**
     * Creates an 'Invalid params' {@link JsonRpcError} (-32602) as defined by the JSON-RPC 2.0 specification.
     * Indicates invalid method parameters.
     *
     * @param data optional, application-defined data providing details about the invalid parameters.
     *             Can be {@code null}.
     * @return a new {@link JsonRpcError} instance representing an invalid parameters error.
     */
    public static JsonRpcError invalidParams(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, data);
    }

    /**
     * Creates an 'Internal error' {@link JsonRpcError} (-32603) as defined by the JSON-RPC 2.0 specification.
     * Indicates an internal JSON-RPC error on the server.
     *
     * @param data optional, application-defined data providing details about the internal error.
     *             Can be {@code null}.
     * @return a new {@link JsonRpcError} instance representing an internal error.
     */
    public static JsonRpcError internalError(@Nullable Object data) {
        return new JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR, data);
    }
}
