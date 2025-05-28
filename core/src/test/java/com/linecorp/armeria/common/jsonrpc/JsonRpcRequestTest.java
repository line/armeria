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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.internal.common.JacksonUtil;

class JsonRpcRequestTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    // Tests for @JsonCreator
    // JsonRpcRequest(@JsonProperty(value = "jsonrpc", required = true) String jsonrpc, ...)
    @Test
    void deserialize_validRequestString_allFieldsPresent() throws JsonProcessingException {
        // Inputs/Preconditions
        final String jsonRequestStr =
                "{" +
                        " \"jsonrpc\": \"2.0\"," +
                        " \"method\": \"getData\"," +
                        " \"params\": {\"filter\": \"active\"}," +
                        " \"id\": \"req-xyz\"" +
                        "}";

        // Execute
        final JsonRpcRequest request = mapper.readValue(jsonRequestStr, JsonRpcRequest.class);

        // Expected Outcomes/Postconditions
        assertNotNull(request);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, request.jsonRpcVersion());
        assertEquals("getData", request.method());
        assertEquals("req-xyz", request.id());
        assertNotNull(request.params());
        assertTrue(request.params().isObject());
        assertEquals(mapper.createObjectNode().put("filter", "active"), request.params());
        assertFalse(request.notificationRequest());
        assertTrue(request.hasObjectParams());
        assertFalse(request.hasArrayParams());
    }

    @Test
    void deserialize_notificationString_noParams_noId() throws JsonProcessingException {
        // Inputs/Preconditions
        final String jsonRequestStr = "{\"jsonrpc\": \"2.0\", \"method\": \"ping\"}";

        // Execute
        final JsonRpcRequest request = mapper.readValue(jsonRequestStr, JsonRpcRequest.class);

        // Expected Outcomes/Postconditions
        assertNotNull(request);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, request.jsonRpcVersion());
        assertEquals("ping", request.method());
        assertNull(request.id());
        assertNull(request.params());
        assertTrue(request.notificationRequest());
        assertFalse(request.hasObjectParams());
        assertFalse(request.hasArrayParams());
    }

    @Test
    void deserialize_missingRequiredMethod_throwsJsonProcessingException() {
        // Inputs/Preconditions - Scenario 1 (method missing)
        // JsonRpcRequest @JsonCreator marks 'method' as required.
        final String jsonRequestStr = "{\"jsonrpc\": \"2.0\", \"id\": \"no-method\"}";

        // Execute & Expected Outcomes/Postconditions
        assertThrows(JsonProcessingException.class, () -> {
            mapper.readValue(jsonRequestStr, JsonRpcRequest.class);
        });
    }

    @Test
    void deserialize_missingRequiredJsonRpcVersion_throwsJsonProcessingException() {
        // Inputs/Preconditions - Scenario 2 (jsonrpc missing)
        // JsonRpcRequest @JsonCreator marks 'jsonrpc' as required.
        final String jsonRequestStr = "{\"method\": \"no-version\", \"id\": \"no-version-id\"}";

        // Execute & Expected Outcomes/Postconditions
        assertThrows(JsonProcessingException.class, () -> {
            mapper.readValue(jsonRequestStr, JsonRpcRequest.class);
        });
    }

    // Tests for Jackson Serialization (Object -> JSON String)
    @Test
    void serialize_requestObject_allFieldsPresent_arrayParams() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonNode params = mapper.valueToTree(java.util.Arrays.asList(10, 5));
        final JsonRpcRequest request =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "calculateSum", params, "calc-id");

        // Execute
        final String jsonString = mapper.writeValueAsString(request);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"jsonrpc":"2.0","method":"calculateSum","params":[10,5],"id":"calc-id"}
        final ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        expectedNode.put("method", "calculateSum");
        expectedNode.set("params", params);
        expectedNode.put("id", "calc-id");

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
    }

    @Test
    void serialize_notificationObject_nullParams_nullId() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcRequest request =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "heartbeat", null, null);

        // Execute
        final String jsonString = mapper.writeValueAsString(request);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"jsonrpc":"2.0","method":"heartbeat"}
        // params and id should be omitted due to @JsonInclude(JsonInclude.Include.NON_NULL)
        final ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        expectedNode.put("method", "heartbeat");

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
        assertThat(actualNode.has("params")).isFalse();
        assertThat(actualNode.has("id")).isFalse();
    }

    // Tests for isNotification()
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

    // Tests for hasArrayParams()
    @Test
    void hasArrayParams_returnsCorrectBoolean() {
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

    // Tests for hasObjectParams()
    @Test
    void hasObjectParams_returnsCorrectBoolean() {
        // Scenario 1: params is ObjectNode
        final JsonNode objectParams = mapper.createObjectNode().put("key", "val");
        final JsonRpcRequest requestWithObjectParams =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "method", objectParams, "id");
        assertTrue(requestWithObjectParams.hasObjectParams());
        assertFalse(requestWithObjectParams.hasArrayParams());

        // Scenario 2: params is ArrayNode
        final JsonNode arrayParams = mapper.createArrayNode().add(1);
        final JsonRpcRequest requestWithArrayParams =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "method", arrayParams, "id");
        assertFalse(requestWithArrayParams.hasObjectParams());
        assertTrue(requestWithArrayParams.hasArrayParams());

        // Scenario 3: params is null
        final JsonRpcRequest requestWithNullParams =
                new JsonRpcRequest(JsonRpcUtil.JSON_RPC_VERSION, "method", null, "id");
        assertFalse(requestWithNullParams.hasObjectParams());
        assertFalse(requestWithNullParams.hasArrayParams());
    }
}
