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
 * Standard JSON-RPC 2.0 error codes.
 * See <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 Specification - Error object</a>
 */
public enum JsonRpcErrorCode {

    INVALID_REQUEST(-32600, "Invalid Request"),

    METHOD_NOT_FOUND(-32601, "Method not found"),

    INVALID_PARAMS(-32602, "Invalid params"),

    INTERNAL_ERROR(-32603, "Internal error"),

    PARSE_ERROR(-32700, "Parse error");

    private final int code;

    private final String message;

    JsonRpcErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Returns the error code number.
     */
    public int code() {
        return code;
    }

    /**
     * Returns the error message.
     */
    public String message() {
        return message;
    }
}
