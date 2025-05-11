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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JsonRpcResponseTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // Tests for ofSuccess(@Nullable Object result, @Nullable Object id)
    @Test
    void ofSuccess_withJsonNodeResultAndStringId() {
        // Inputs/Preconditions
        final JsonNode resultNode = mapper.createObjectNode().put("status", "ok");
        final String id = "success-id-1";

        // Execute
        final JsonRpcResponse response = JsonRpcResponse.ofSuccess(resultNode, id);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals("2.0", response.jsonRpcVersion());
        assertEquals(resultNode, response.result());
        assertNull(response.error());
        assertEquals(id, response.id());
    }

    @Test
    void ofSuccess_withNullResultAndNullId() {
        // Inputs/Preconditions
        final Object result = null;
        final Object id = null;

        // Execute
        final JsonRpcResponse response = JsonRpcResponse.ofSuccess(result, id);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals("2.0", response.jsonRpcVersion());
        assertNull(response.result());
        assertNull(response.error());
        assertNull(response.id());
    }

    // Tests for ofError(JsonRpcError error, @Nullable Object id)
    @Test
    void ofError_withErrorObjectAndNumberId() {
        // Inputs/Preconditions
        final JsonRpcError error = JsonRpcError.parseError("Details");
        final Number id = 12345;

        // Execute
        final JsonRpcResponse response = JsonRpcResponse.ofError(error, id);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals("2.0", response.jsonRpcVersion());
        assertNull(response.result());
        assertEquals(error, response.error());
        assertEquals(id, response.id());
    }

    // Tests for @JsonCreator JsonRpcResponse(@JsonProperty("jsonrpc") String jsonrpc, ...)
    @Test
    void deserialize_successfulJsonResponseString() throws JsonProcessingException {
        // Inputs/Preconditions
        final String jsonResponseStr =
                "{\"jsonrpc\": \"2.0\", \"result\": {\"data\": \"content\"}, \"id\": \"resp-1\"}";

        // Execute
        final JsonRpcResponse response = mapper.readValue(jsonResponseStr, JsonRpcResponse.class);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals("2.0", response.jsonRpcVersion());
        assertNotNull(response.result());
        assertEquals(mapper.createObjectNode().put("data", "content"), mapper.valueToTree(response.result()));
        assertNull(response.error());
        assertEquals("resp-1", response.id());
    }

    @Test
    void deserialize_errorJsonResponseString() throws JsonProcessingException {
        // Inputs/Preconditions
        final String jsonResponseStr =
                "{" +
                "\"jsonrpc\": \"2.0\", " +
                "\"error\": {\"code\": -32600, \"message\": \"Invalid Request\"}, " +
                "\"id\": null" +
                "}";

        // Execute
        final JsonRpcResponse response = mapper.readValue(jsonResponseStr, JsonRpcResponse.class);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals("2.0", response.jsonRpcVersion());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(-32600, response.error().code());
        assertEquals("Invalid Request", response.error().message());
        assertNull(response.error().data());
        assertNull(response.id());
    }

    @Test
    void deserialize_missingFields_setsFieldsToNull() throws JsonProcessingException {
        // Inputs/Preconditions - Scenario 1 (id missing)
        final String jsonResponseStr1 = "{\"jsonrpc\": \"2.0\", \"result\": \"ok\"}";

        // Execute
        final JsonRpcResponse response1 = mapper.readValue(jsonResponseStr1, JsonRpcResponse.class);

        // Expected Outcomes/Postconditions
        assertNotNull(response1);
        assertEquals("2.0", response1.jsonRpcVersion());
        assertEquals("ok", response1.result());
        assertNull(response1.error());
        assertNull(response1.id()); // ID is missing, should be null

        // Inputs/Preconditions - Scenario 2 (result and error both missing)
        final String jsonResponseStr2 = "{\"jsonrpc\": \"2.0\", \"id\": \"only-id\"}";

        // Execute
        final JsonRpcResponse response2 = mapper.readValue(jsonResponseStr2, JsonRpcResponse.class);

        // Expected Outcomes/Postconditions
        assertNotNull(response2);
        assertEquals("2.0", response2.jsonRpcVersion());
        assertNull(response2.result()); // Result is missing, should be null
        assertNull(response2.error());  // Error is missing, should be null
        assertEquals("only-id", response2.id());
    }

    // Tests for Jackson Serialization (Object -> JSON String)
    @Test
    void serialize_successfulResponseObject() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcResponse response =
                JsonRpcResponse.ofSuccess(mapper.getNodeFactory().textNode("success data"), "ser-id-1");

        // Execute
        final String jsonString = mapper.writeValueAsString(response);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"jsonrpc":"2.0","result":"success data","id":"ser-id-1"}
        // error field should be absent due to @JsonInclude(JsonInclude.Include.NON_NULL)
        final com.fasterxml.jackson.databind.node.ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("jsonrpc", "2.0");
        expectedNode.put("result", "success data");
        expectedNode.put("id", "ser-id-1");

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
        assertThat(actualNode.has("error")).isFalse();
    }

    @Test
    void serialize_errorResponseObject() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcError error = new JsonRpcError(-32000, "Custom Error", null);
        final JsonRpcResponse response = JsonRpcResponse.ofError(error, null);

        // Execute
        final String jsonString = mapper.writeValueAsString(response);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"jsonrpc":"2.0","error":{"code":-32000,"message":"Custom Error"},"id":null}
        // result field should be absent. data field in error should be absent if null.
        final com.fasterxml.jackson.databind.node.ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("jsonrpc", "2.0");
        final com.fasterxml.jackson.databind.node.ObjectNode errorNode = mapper.createObjectNode();
        errorNode.put("code", -32000);
        errorNode.put("message", "Custom Error");
        expectedNode.set("error", errorNode);
        expectedNode.set("id", mapper.nullNode());

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
        assertThat(actualNode.has("result")).isFalse();
        assertThat(actualNode.get("error").has("data")).isFalse();
    }

    @Test
    void serialize_nullId_isExplicitlyIncludedAsNull() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcResponse response = JsonRpcResponse.ofSuccess("data", null);

        // Execute
        final String jsonString = mapper.writeValueAsString(response);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"jsonrpc":"2.0","result":"data","id":null}
        // The "id" field should be present and explicitly null.
        final com.fasterxml.jackson.databind.node.ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("jsonrpc", "2.0");
        expectedNode.put("result", "data");
        expectedNode.set("id", mapper.nullNode());

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
        assertThat(actualNode.has("id")).isTrue();
        assertThat(actualNode.get("id").isNull()).isTrue();
    }
}
