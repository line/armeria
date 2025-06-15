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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JsonRpcResponseTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ofSuccess_withJsonNodeResultAndStringId() {
        final JsonNode resultNode = mapper.createObjectNode().put("status", "ok");
        final String id = "success-id-1";

        final JsonRpcResponse response = JsonRpcResponse.ofSuccess(resultNode, id);

        assertNotNull(response);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, response.jsonRpcVersion());
        assertEquals(resultNode, response.result());
        assertNull(response.error());
        assertEquals(id, response.id());
    }

    @Test
    void ofSuccess_withNullResultAndNullId() {
        final Object result = null;
        final Object id = null;

        assertThrows(NullPointerException.class, () -> {
            JsonRpcResponse.ofSuccess(result, id);
        });
    }

    @Test
    void ofError_withErrorObjectAndNumberId() {
        final JsonRpcError error = JsonRpcError.PARSE_ERROR.withData("Details");
        final Number id = 12345;

        final JsonRpcResponse response = JsonRpcResponse.ofError(error, id);

        assertNotNull(response);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, response.jsonRpcVersion());
        assertNull(response.result());
        assertEquals(error, response.error());
        assertEquals(id, response.id());
    }

    @Test
    void ofError_withNullErrorAndNullId() {
        final JsonRpcError error = null;
        final Object id = null;

        assertThrows(NullPointerException.class, () -> {
            JsonRpcResponse.ofError(error, id);
        });
    }
}
