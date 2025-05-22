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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class JsonRpcErrorTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // Test for @JsonCreator JsonRpcError(int code, String message, @Nullable Object data)
    @Test
    void constructor_withCodeMessageAndData() {
        // Inputs/Preconditions
        final int code = -32001;
        final String message = "Application specific error";
        final String data = "Error details here";

        // Execute
        final JsonRpcError error = new JsonRpcError(code, message, data);

        // Expected Outcomes/Postconditions
        assertNotNull(error);
        assertEquals(code, error.code());
        assertEquals(message, error.message());
        assertEquals(data, error.data());
    }

    // Test for constructor JsonRpcError(int code, String message)
    @Test
    void constructor_withCodeAndMessage_nullData() {
        // Inputs/Preconditions
        final int code = -32002;
        final String message = "Another error without data";

        // Execute
        final JsonRpcError error = new JsonRpcError(code, message);

        // Expected Outcomes/Postconditions
        assertNotNull(error);
        assertEquals(code, error.code());
        assertEquals(message, error.message());
        assertNull(error.data());
    }

    // Tests for static factory methods
    @Test
    void staticFactoryMethods_createCorrectErrorObjects() {
        // JsonRpcError.parseError(Object data)
        final JsonRpcError parseErrorNullData = JsonRpcError.PARSE_ERROR;
        assertEquals(-32700, parseErrorNullData.code());
        assertEquals("Parse error", parseErrorNullData.message());
        assertNull(parseErrorNullData.data());

        final String parseErrorDataStr = "Parse detail";
        final JsonRpcError parseErrorWithData = JsonRpcError.PARSE_ERROR.withData(parseErrorDataStr);
        assertEquals(-32700, parseErrorWithData.code());
        assertEquals("Parse error", parseErrorWithData.message());
        assertEquals(parseErrorDataStr, parseErrorWithData.data());

        // JsonRpcError.invalidRequest(Object data)
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

        // JsonRpcError.methodNotFound(Object data)
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

        // JsonRpcError.invalidParams(Object data)
        final JsonRpcError invalidParamsNullData = JsonRpcError.INVALID_PARAMS;
        assertEquals(-32602, invalidParamsNullData.code());
        assertEquals("Invalid params", invalidParamsNullData.message());
        assertNull(invalidParamsNullData.data());

        final List<String> invalidParamsDataList = Arrays.asList("param1", "param2");
        final JsonRpcError invalidParamsWithData = JsonRpcError.INVALID_PARAMS.withData(invalidParamsDataList);
        assertEquals(-32602, invalidParamsWithData.code());
        assertEquals("Invalid params", invalidParamsWithData.message());
        assertEquals(invalidParamsDataList, invalidParamsWithData.data());

        // JsonRpcError.internalError(Object data)
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

    // Tests for Jackson Serialization (Object -> JSON String)
    @Test
    void serialize_errorObject_withData() throws JsonProcessingException {
        // Inputs/Preconditions
        final java.util.Map<String, String> dataMap = new java.util.HashMap<>();
        dataMap.put("info", "details here");
        final JsonRpcError error = new JsonRpcError(-32000, "Server Error", dataMap);

        // Execute
        final String jsonString = mapper.writeValueAsString(error);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"code":-32000,"message":"Server Error","data":{"info":"details here"}}
        final ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("code", -32000);
        expectedNode.put("message", "Server Error");
        expectedNode.set("data", mapper.valueToTree(dataMap));

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
    }

    @Test
    void serialize_errorObject_nullData_omitsDataField() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcError error = JsonRpcError.INTERNAL_ERROR;

        // Execute
        final String jsonString = mapper.writeValueAsString(error);

        // Expected Outcomes/Postconditions
        assertNotNull(jsonString);
        // Expected: {"code":-32603,"message":"Internal error"}
        // data field should be omitted due to @JsonInclude(JsonInclude.Include.NON_NULL)
        final ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("code", JsonRpcError.INTERNAL_ERROR.code());
        expectedNode.put("message", JsonRpcError.INTERNAL_ERROR.message());

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
        assertThat(actualNode.has("data")).isFalse();
    }
}
