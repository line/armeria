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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class JsonRpcErrorTest {

    @Test
    void staticFactoryMethods_createCorrectErrorObjects() {
        final JsonRpcError parseErrorNullData = JsonRpcError.PARSE_ERROR;
        assertEquals(-32700, parseErrorNullData.code());
        assertEquals("Parse error", parseErrorNullData.message());
        assertNull(parseErrorNullData.data());

        final String parseErrorDataStr = "Parse detail";
        final JsonRpcError parseErrorWithData = JsonRpcError.PARSE_ERROR.withData(parseErrorDataStr);
        assertEquals(-32700, parseErrorWithData.code());
        assertEquals("Parse error", parseErrorWithData.message());
        assertEquals(parseErrorDataStr, parseErrorWithData.data());

        final JsonRpcError invalidRequestNullData = JsonRpcError.INVALID_REQUEST;
        assertEquals(-32600, invalidRequestNullData.code());
        assertEquals("Invalid Request", invalidRequestNullData.message());
        assertNull(invalidRequestNullData.data());

        final String invalidRequestDataStr = "Request format issue";
        final JsonRpcError invalidRequestWithData =
                JsonRpcError.INVALID_REQUEST.withData(invalidRequestDataStr);
        assertEquals(-32600, invalidRequestWithData.code());
        assertEquals("Invalid Request", invalidRequestWithData.message());
        assertEquals(invalidRequestDataStr, invalidRequestWithData.data());

        final JsonRpcError methodNotFoundNullData = JsonRpcError.METHOD_NOT_FOUND;
        assertEquals(-32601, methodNotFoundNullData.code());
        assertEquals("Method not found", methodNotFoundNullData.message());
        assertNull(methodNotFoundNullData.data());

        final java.util.Map<String, String> methodNotFoundDataMap =
                java.util.Collections.singletonMap("method", "missingMethod");
        final JsonRpcError methodNotFoundWithData =
                JsonRpcError.METHOD_NOT_FOUND.withData(methodNotFoundDataMap);
        assertEquals(-32601, methodNotFoundWithData.code());
        assertEquals("Method not found", methodNotFoundWithData.message());
        assertEquals(methodNotFoundDataMap, methodNotFoundWithData.data());

        final JsonRpcError invalidParamsNullData = JsonRpcError.INVALID_PARAMS;
        assertEquals(-32602, invalidParamsNullData.code());
        assertEquals("Invalid params", invalidParamsNullData.message());
        assertNull(invalidParamsNullData.data());

        final List<String> invalidParamsDataList = Arrays.asList("param1", "param2");
        final JsonRpcError invalidParamsWithData = JsonRpcError.INVALID_PARAMS.withData(invalidParamsDataList);
        assertEquals(-32602, invalidParamsWithData.code());
        assertEquals("Invalid params", invalidParamsWithData.message());
        assertEquals(invalidParamsDataList, invalidParamsWithData.data());

        final JsonRpcError internalErrorNullData = JsonRpcError.INTERNAL_ERROR;
        assertEquals(-32603, internalErrorNullData.code());
        assertEquals("Internal error", internalErrorNullData.message());
        assertNull(internalErrorNullData.data());

        final String internalErrorDataStr = "Internal issue details";
        final JsonRpcError internalErrorWithData = JsonRpcError.INTERNAL_ERROR.withData(internalErrorDataStr);
        assertEquals(-32603, internalErrorWithData.code());
        assertEquals("Internal error", internalErrorWithData.message());
        assertEquals(internalErrorDataStr, internalErrorWithData.data());
    }
}
