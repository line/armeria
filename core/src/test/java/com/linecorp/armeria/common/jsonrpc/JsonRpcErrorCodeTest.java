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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonRpcErrorCodeTest {

    @Test
    void code_returnsCorrectIntegerCode() {
        assertEquals(-32600, JsonRpcErrorCode.INVALID_REQUEST.code());
        assertEquals(-32601, JsonRpcErrorCode.METHOD_NOT_FOUND.code());
        assertEquals(-32602, JsonRpcErrorCode.INVALID_PARAMS.code());
        assertEquals(-32603, JsonRpcErrorCode.INTERNAL_ERROR.code());
        assertEquals(-32700, JsonRpcErrorCode.PARSE_ERROR.code());
    }

    @Test
    void message_returnsCorrectDefaultMessage() {
        assertEquals("Invalid Request", JsonRpcErrorCode.INVALID_REQUEST.message());
        assertEquals("Method not found", JsonRpcErrorCode.METHOD_NOT_FOUND.message());
        assertEquals("Invalid params", JsonRpcErrorCode.INVALID_PARAMS.message());
        assertEquals("Internal error", JsonRpcErrorCode.INTERNAL_ERROR.message());
        assertEquals("Parse error", JsonRpcErrorCode.PARSE_ERROR.message());
    }
}
