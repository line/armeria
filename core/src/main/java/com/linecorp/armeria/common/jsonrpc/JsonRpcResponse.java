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
 * Represents a JSON-RPC 2.0 response object.
 * A response object has the following members upon successful completion:
 * <ul>
 *   <li>{@code jsonrpc}: A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0".</li>
 *   <li>{@code result}: This member is REQUIRED on success. This member MUST NOT exist if there was an error
 *       invoking the method. The value of this member is determined by the method invoked on the Server.</li>
 *   <li>{@code id}: This member is REQUIRED. It MUST be the same as the value of the id member in the
 *       Request Object. If there was an error in detecting the id in the Request object
 *       (e.g. Parse error/Invalid Request), it MUST be Null.</li>
 * </ul>
 * When a rpc call encounters an error, the response object has the following members:
 * <ul>
 *   <li>{@code jsonrpc}: A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0".</li>
 *   <li>{@code error}: This member is REQUIRED on error. This member MUST NOT exist if there was no error
 *       invoking the method. The value for this member MUST be an Object as defined in
 *       {@link JsonRpcError}.</li>
 *   <li>{@code id}: Same as for successful completion.</li>
 * </ul>
 * This class is designed for easy serialization to and deserialization from JSON using Jackson annotations.
 * Notifications do not receive a {@link JsonRpcResponse}.
 *
 * @see <a href="https://www.jsonrpc.org/specification#response_object">
 *     JSON-RPC 2.0 Specification - Response object</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // Omit null fields (like result or error) during serialization
public final class JsonRpcResponse {

    /**
     * The required JSON-RPC protocol version string.
     */
    private static final String JSONRPC_VERSION = "2.0";

    /**
     * A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0".
     */
    private final String jsonrpc;

    /**
     * The result of the method invocation. This field is present only for successful responses.
     * Its value is determined by the server-side method invoked.
     */
    @Nullable
    private final Object result;

    /**
     * An error object if an error occurred during method invocation.
     * This field is present only for error responses.
     */
    @Nullable
    private final JsonRpcError error;

    /**
     * The identifier established by the Client in the corresponding request.
     * It MUST be the same as the value of the id member in the Request Object.
     * If there was an error in detecting the id in the Request object (e.g. Parse error/Invalid Request),
     * it MUST be {@code null}.
     */
    @Nullable
    private final Object id;

    /**
     * Private constructor for creating a successful response.
     *
     * @param result the result of the method invocation. Can be any valid JSON value.
     * @param id the ID of the original request.
     */
    private JsonRpcResponse(@Nullable Object result, @Nullable Object id) {
        this.jsonrpc = JSONRPC_VERSION; // Fixed value for JSON-RPC version
        this.result = result;
        this.error = null;
        this.id = id;
    }

    /**
     * Private constructor for creating an error response.
     *
     * @param error the {@link JsonRpcError} object detailing the error.
     * @param id the ID of the original request, or {@code null} if the request ID could not be determined.
     */
    private JsonRpcResponse(JsonRpcError error, @Nullable Object id) {
        this.jsonrpc = JSONRPC_VERSION; // Fixed value for JSON-RPC version
        this.result = null;
        this.error = error;
        this.id = id;
    }

    /**
     * Creates a new JSON-RPC response instance.
     * This constructor is annotated with {@link JsonCreator} and {@link JsonProperty} to be used by
     * Jackson for deserializing a JSON response object into a {@link JsonRpcResponse} instance.
     *
     * <p>Note: According to the JSON-RPC 2.0 specification, exactly one of {@code result} or {@code error}
     * MUST be present. This constructor allows both to be specified (though typically one will be null
     * during deserialization based on the JSON source), but factory methods {@link #ofSuccess(Object, Object)}
     * and {@link #ofError(JsonRpcError, Object)} ensure this rule for programmatic creation.
     *
     * @param jsonrpc the JSON-RPC protocol version string. Should be "2.0".
     * @param result  the result of the method invocation. Should be non-null
     *                if {@code error} is {@code null}.
     * @param error   the error object if an error occurred. Should be non-null
     *                if {@code result} is {@code null}.
     * @param id      the request identifier from the original request. This is required and should match
     *                the request ID, or be {@code null} if the request ID could not be determined
     *                (e.g., parse error).
     */
    @JsonCreator
    public JsonRpcResponse(@JsonProperty("jsonrpc") String jsonrpc,
                           @JsonProperty("result") @Nullable Object result,
                           @JsonProperty("error") @Nullable JsonRpcError error,
                           @JsonProperty("id") @Nullable Object id) {
        this.jsonrpc = jsonrpc;
        this.result = result;
        this.error = error;
        this.id = id;
    }

    /**
     * Creates a successful JSON-RPC response.
     * The response will contain
     * the provided {@code result} and {@code id}, and the {@code error} field will be absent.
     *
     * @param result the result of the method invocation. This can be any value that can be serialized to JSON.
     *               While {@code null} is a valid JSON value,
     *               consider if it truly represents success or if an error
     *               or different representation is more appropriate.
     * @param id     the ID of the original request this response corresponds to. It should match the ID from
     *               the {@link JsonRpcRequest}. Can be a {@link String}, a {@link Number}, or {@code null}
     *               (though {@code null} ID
     *               for a successful response is unusual unless the request ID was also null).
     * @return a new {@link JsonRpcResponse} instance representing a successful outcome.
     */
    public static JsonRpcResponse ofSuccess(@Nullable Object result, @Nullable Object id) {
        return new JsonRpcResponse(result, id);
    }

    /**
     * Creates an error JSON-RPC response.
     * The response will contain the provided {@link JsonRpcError} and {@code id},
     * and the {@code result} field will be absent.
     *
     * @param error the {@link JsonRpcError} object detailing the error that occurred. Must not be {@code null}.
     * @param id    the ID of the original request this response corresponds to. It should match the ID from
     *              the {@link JsonRpcRequest}. Can be a {@link String}, a {@link Number}, or {@code null}
     *              (especially if the error occurred before the request ID could be parsed,
     *              e.g., a Parse Error).
     * @return a new {@link JsonRpcResponse} instance representing an error outcome.
     */
    public static JsonRpcResponse ofError(JsonRpcError error, @Nullable Object id) {
        return new JsonRpcResponse(error, id);
    }

    /**
     * Returns the JSON-RPC protocol version string.
     * According to the JSON-RPC 2.0 specification, this MUST be exactly "2.0".
     *
     * @return the JSON-RPC version string, which should be "2.0".
     */
    @JsonProperty("jsonrpc")
    public String jsonRpcVersion() {
        return jsonrpc;
    }

    /**
     * Returns the result of the method invocation.
     * This field is present and non-null for successful responses and {@code null} for error responses.
     *
     * @return the result object, or {@code null} if the response represents an error or if the successful
     *         result was explicitly {@code null}.
     */
    @JsonProperty
    @Nullable
    public Object result() {
        return result;
    }

    /**
     * Returns the error object if an error occurred during the method invocation.
     * This field is present and non-null for error responses and {@code null} for successful responses.
     *
     * @return the {@link JsonRpcError} object, or {@code null} if the response represents a successful outcome.
     */
    @JsonProperty
    @Nullable
    public JsonRpcError error() {
        return error;
    }

    /**
     * Returns the request identifier (ID) that this response corresponds to.
     * This MUST be the same as the value of the {@code id} member in the {@link JsonRpcRequest}.
     * If there was an error in detecting the {@code id} in the Request object
     * (e.g., Parse error/Invalid Request),
     * this value MUST be {@code null}.
     *
     * <p>The {@link JsonProperty} annotation ensures this field is included in JSON serialization.
     * The {@link JsonInclude @JsonInclude(JsonInclude.Include.ALWAYS)} annotation ensures the "id" field
     * is always present in the JSON output, even if its value is {@code null},
     * as required by the specification
     * for responses to requests where the ID was determinable
     * (even if the request itself was flawed leading to an error).
     *
     * @return the request ID. Can be a {@link String}, a {@link Number}, or {@code null}.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS) // Ensure 'id' is always present, even if null
    @JsonProperty
    @Nullable
    public Object id() {
        return id;
    }
}
