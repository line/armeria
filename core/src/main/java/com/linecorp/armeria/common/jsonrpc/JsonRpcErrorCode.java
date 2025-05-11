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

/**
 * Defines standard error codes as specified by the JSON-RPC 2.0 specification.
 * These error codes are used in the "code" member of a JSON-RPC error object.
 *
 * <p>The error codes from -32768 to -32000 are reserved for pre-defined errors.
 * Any code within this range, but not defined explicitly below, is reserved for future use.
 *
 * @see JsonRpcError
 * @see <a href="https://www.jsonrpc.org/specification#error_object">
 *     JSON-RPC 2.0 Specification - Error object</a>
 */
public enum JsonRpcErrorCode {

    /**
     * Invalid Request (-32600).
     * The JSON sent is not a valid Request object.
     */
    INVALID_REQUEST(-32600, "Invalid Request"),

    /**
     * Method not found (-32601).
     * The method does not exist / is not available.
     */
    METHOD_NOT_FOUND(-32601, "Method not found"),

    /**
     * Invalid params (-32602).
     * Invalid method parameter(s).
     */
    INVALID_PARAMS(-32602, "Invalid params"),

    /**
     * Internal error (-32603).
     * Internal JSON-RPC error.
     */
    INTERNAL_ERROR(-32603, "Internal error"),

    /**
     * Parse error (-32700).
     * Invalid JSON was received by the server.
     * An error occurred on the server while parsing the JSON text.
     */
    PARSE_ERROR(-32700, "Parse error");

    /**
     * The integer representation of the JSON-RPC error code.
     */
    private final int code;

    /**
     * The default, human-readable message associated with this error code.
     */
    private final String message;

    /**
     * Constructs a new {@link JsonRpcErrorCode} with the specified integer code and default message.
     *
     * @param code the integer error code.
     * @param message the default human-readable string description of the error.
     */
    JsonRpcErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Returns the integer value of the error code.
     *
     * @return the error code as an integer.
     */
    public int code() {
        return code;
    }

    /**
     * Returns the default, human-readable string describing the error.
     *
     * @return the default error message.
     */
    public String message() {
        return message;
    }
}
