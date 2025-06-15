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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.internal.common.JacksonUtil;

class JsonRpcRequestTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    @Test
    void deserialize_missingRequiredMethod_throwsJsonProcessingException() {
        final String jsonRequestStr = "{\"jsonrpc\": \"2.0\", \"id\": \"no-method\"}";

        assertThrows(JsonProcessingException.class, () -> {
            mapper.readValue(jsonRequestStr, JsonRpcRequest.class);
        });
    }

    @Test
    void deserialize_missingRequiredJsonRpcVersion_throwsJsonProcessingException() {
        final String jsonRequestStr = "{\"method\": \"no-version\", \"id\": \"no-version-id\"}";

        assertThrows(JsonProcessingException.class, () -> {
            mapper.readValue(jsonRequestStr, JsonRpcRequest.class);
        });
    }

    @Test
    void isNotification_returnsCorrectBoolean() {
        // Scenario 1: ID is null (Notification)
        final JsonRpcRequest notification =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "notify", null, null);
        assertTrue(notification.notificationRequest());

        // Scenario 2: ID is present (Request)
        final JsonRpcRequest requestWithId =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "request", null, "id-1");
        assertFalse(requestWithId.notificationRequest());

        // Scenario 3: ID is a number (Request)
        final JsonRpcRequest requestWithNumberId =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "requestNum", null, 123);
        assertFalse(requestWithNumberId.notificationRequest());
    }

    @Test
    void hasParams_returnsCorrectBoolean() {
        // Scenario 1: params is ArrayNode
        final JsonNode arrayParams = mapper.createArrayNode().add(1);
        final JsonRpcRequest requestWithArrayParams =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "method", arrayParams, "id");
        assertTrue(requestWithArrayParams.hasArrayParams());
        assertFalse(requestWithArrayParams.hasObjectParams());

        // Scenario 2: params is ObjectNode
        final JsonNode objectParams = mapper.createObjectNode().put("key", "val");
        final JsonRpcRequest requestWithObjectParams =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "method", objectParams, "id");
        assertFalse(requestWithObjectParams.hasArrayParams());
        assertTrue(requestWithObjectParams.hasObjectParams());

        // Scenario 3: params is null
        final JsonRpcRequest requestWithNullParams =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "method", null, "id");
        assertFalse(requestWithNullParams.hasArrayParams());
        assertFalse(requestWithNullParams.hasObjectParams());
    }
}
