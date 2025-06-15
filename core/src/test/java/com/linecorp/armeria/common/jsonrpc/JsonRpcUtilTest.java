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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

class JsonRpcUtilTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseDelegateResponse_successfulHttpAndValidJsonBody() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON_UTF_8,
                "{\"data\": \"value\"}");
        final Object id = "test-id-1";
        final String methodName = "testMethod";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.error());

        final JsonNode expectedResult = mapper.createObjectNode().put("data", "value");
        assertEquals(expectedResult, rpcResponse.result());
    }

    @Test
    void parseDelegateResponse_successfulHttp204NoContentAndEmptyBody() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(HttpStatus.NO_CONTENT);
        final Object id = "test-id-2";
        final String methodName = "testMethodNoContent";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.error());
        assertEquals(mapper.getNodeFactory().nullNode(), rpcResponse.result());
    }

    @Test
    void parseDelegateResponse_successfulHttpButInvalidJsonBody() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON_UTF_8,
                "invalid-json");
        final Object id = "test-id-3";
        final String methodName = "testMethodInvalidJson";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.result());

        assertNotNull(rpcResponse.error());
        assertEquals(JsonRpcError.INTERNAL_ERROR.code(), rpcResponse.error().code());
        assertEquals("Internal error", rpcResponse.error().message());
    }

    @Test
    void parseDelegateResponse_errorHttp404NotFound() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.NOT_FOUND,
                MediaType.PLAIN_TEXT_UTF_8,
                "Delegate method not found");
        final Object id = "test-id-4";
        final String methodName = "nonExistentMethod";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.result());

        assertNotNull(rpcResponse.error());
        assertEquals(JsonRpcError.METHOD_NOT_FOUND.code(), rpcResponse.error().code());
        assertEquals("Method not found", rpcResponse.error().message());
    }

    @Test
    void parseDelegateResponse_errorHttp400BadRequest_withBody() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.BAD_REQUEST,
                MediaType.PLAIN_TEXT_UTF_8,
                "Invalid parameter X");
        final Object id = "test-id-5a";
        final String methodName = "methodWithBadParam";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.result());

        assertNotNull(rpcResponse.error());
        assertEquals(JsonRpcError.INVALID_PARAMS.code(), rpcResponse.error().code());
        assertEquals("Invalid params", rpcResponse.error().message());
    }

    @Test
    void parseDelegateResponse_errorHttp400BadRequest_emptyBody() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(HttpStatus.BAD_REQUEST);
        final Object id = "test-id-5b";
        final String methodName = "methodWithBadParamEmpty";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.result());

        assertNotNull(rpcResponse.error());
        assertEquals(JsonRpcError.INVALID_PARAMS.code(), rpcResponse.error().code());
        assertEquals("Invalid params", rpcResponse.error().message());
    }

    @Test
    void parseDelegateResponse_otherClientErrorHttp401Unauthorized() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.UNAUTHORIZED,
                MediaType.PLAIN_TEXT_UTF_8,
                "Auth failed");
        final Object id = "test-id-6";
        final String methodName = "protectedMethod";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.result());

        assertNotNull(rpcResponse.error());
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), rpcResponse.error().code());
        assertEquals("Invalid Request", rpcResponse.error().message());
    }

    @Test
    void parseDelegateResponse_serverErrorHttp5xx() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                MediaType.PLAIN_TEXT_UTF_8,
                "Server crashed");
        final Object id = "test-id-7";
        final String methodName = "buggyMethod";

        final JsonRpcResponse rpcResponse =
                JsonRpcUtil.parseDelegateResponse(delegateResponse, id, methodName, mapper);

        assertNotNull(rpcResponse);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, rpcResponse.jsonRpcVersion());
        assertEquals(id, rpcResponse.id());
        assertNull(rpcResponse.result());

        assertNotNull(rpcResponse.error());
        assertEquals(JsonRpcError.INTERNAL_ERROR.code(), rpcResponse.error().code());
        assertEquals("Internal error", rpcResponse.error().message());
    }

    @Test
    void parseJsonNodeToRequest_validRequest_stringId_arrayParams() throws JsonProcessingException {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("method", "subtract");
        itemNode.set("params", mapper.createArrayNode().add(42).add(23));
        itemNode.put("id", "req-1");

        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);

        assertNotNull(request);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, request.jsonRpcVersion());
        assertEquals("subtract", request.method());
        assertEquals("req-1", request.id());
        assertNotNull(request.params());
        assertThat(request.params().isArray()).isTrue();
        assertEquals(mapper.createArrayNode().add(42).add(23), request.params());
        assertThat(request.notificationRequest()).isFalse();
    }

    @Test
    void parseJsonNodeToRequest_validRequest_numberId_objectParams() throws JsonProcessingException {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("method", "update");
        final ObjectNode paramsNode = mapper.createObjectNode();
        paramsNode.put("name", "foo");
        paramsNode.put("value", "bar");
        itemNode.set("params", paramsNode);
        itemNode.put("id", 123);

        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);

        assertNotNull(request);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, request.jsonRpcVersion());
        assertEquals("update", request.method());
        assertEquals(123, request.id());
        assertNotNull(request.params());
        assertThat(request.params().isObject()).isTrue();
        assertEquals(paramsNode, request.params());
        assertThat(request.notificationRequest()).isFalse();
    }

    @Test
    void parseJsonNodeToRequest_validNotification_nullId_noParams() throws JsonProcessingException {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("method", "notify_event");
        itemNode.set("id", mapper.nullNode());

        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);

        assertNotNull(request);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, request.jsonRpcVersion());
        assertEquals("notify_event", request.method());
        assertNull(request.id());
        assertNull(request.params());
        assertThat(request.notificationRequest()).isTrue();
    }

    @Test
    void parseJsonNodeToRequest_validNotification_missingId_noParams() throws JsonProcessingException {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("method", "notify_event");

        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);

        assertNotNull(request);
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, request.jsonRpcVersion());
        assertEquals("notify_event", request.method());
        assertNull(request.id());
        assertNull(request.params());
        assertThat(request.notificationRequest()).isTrue();
    }

    @Test
    void parseJsonNodeToRequest_itemNodeNotJsonObject_throwsIllegalArgumentException() {
        final JsonNode itemNode = mapper.createArrayNode().add(1).add(2).add(3);

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);
        });
        assertEquals("Request item must be a JSON object.", exception.getMessage());
    }

    @Test
    void parseJsonNodeToRequest_invalidJsonRpcVersion_throwsIllegalArgumentException() {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", "1.0");
        itemNode.put("method", "test");
        itemNode.put("id", 1);

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);
        });
        assertEquals("jsonrpc must be '2.0', but was: 1.0", exception.getMessage());
    }

    @Test
    void parseJsonNodeToRequest_missingMethod_throwsIllegalArgumentException() {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("id", 1);

        final java.lang.NullPointerException exception =
                assertThrows(java.lang.NullPointerException.class, () -> {
                    JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);
                });
        assertThat(exception.getMessage()).contains("method");
    }

    @Test
    void parseJsonNodeToRequest_emptyMethod_throwsIllegalArgumentException() {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("method", "");
        itemNode.put("id", 1);

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);
        });
        assertEquals("method must not be empty", exception.getMessage());
    }

    @Test
    void parseJsonNodeToRequest_paramsNotArrayOrObject_throwsIllegalArgumentException() {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("method", "test");
        itemNode.put("params", "not-an-object-or-array");
        itemNode.put("id", 1);

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);
        });
        assertEquals("JSON-RPC request 'params' must be an object or an array, but was: STRING",
                exception.getMessage());
    }

    @Test
    void parseJsonNodeToRequest_missingRequiredField_JacksonThrowsJsonProcessingException() {
        final ObjectNode itemNode = mapper.createObjectNode();
        itemNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        itemNode.put("id", "jackson-fail-id");

        final java.lang.NullPointerException exception =
                assertThrows(java.lang.NullPointerException.class, () -> {
                    JsonRpcUtil.parseJsonNodeToRequest(itemNode, mapper);
                });
        assertThat(exception.getMessage()).contains("method");
    }

    @Test
    void createJsonRpcRequestJsonString_validInput_stringId_listParams() throws JsonProcessingException {
        final String method = "sum";
        final java.util.List<Integer> params = java.util.Arrays.asList(1, 2, 3);
        final String id = "req-abc";

        final String jsonString = JsonRpcUtil.createJsonRpcRequestJsonString(method, params, id, mapper);

        assertNotNull(jsonString);
        final JsonNode expectedNode = mapper.createObjectNode()
                .put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION)
                .put("method", "sum")
                .set("params", mapper.valueToTree(params));
        ((com.fasterxml.jackson.databind.node.ObjectNode) expectedNode).put("id", "req-abc");

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode, actualNode);
    }

    @Test
    void createJsonRpcRequestJsonString_notification_nullId_nullParams() throws JsonProcessingException {
        final String method = "log_event";
        final Object params = null;
        final Object id = null;

        final String jsonString = JsonRpcUtil.createJsonRpcRequestJsonString(method, params, id, mapper);

        assertNotNull(jsonString);

        final ObjectNode expectedNode = mapper.createObjectNode();
        expectedNode.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        expectedNode.put("method", "log_event");
        expectedNode.set("id", mapper.nullNode());

        final JsonNode actualNode = mapper.readTree(jsonString);
        assertEquals(expectedNode.get("jsonrpc"), actualNode.get("jsonrpc"));
        assertEquals(expectedNode.get("method"), actualNode.get("method"));
        assertThat(actualNode.has("id")).isTrue();
        assertThat(actualNode.get("id").isNull()).isTrue();
        assertThat(actualNode.has("params")).isFalse();
    }

    @Test
    void createJsonRpcRequestJsonString_invalidIdType_throwsIllegalArgumentException() {
        final String method = "test";
        final Object params = null;
        final Boolean id = true;

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcUtil.createJsonRpcRequestJsonString(method, params, id, mapper);
        });
        assertEquals("Unsupported ID type: java.lang.Boolean", exception.getMessage());
    }
}
