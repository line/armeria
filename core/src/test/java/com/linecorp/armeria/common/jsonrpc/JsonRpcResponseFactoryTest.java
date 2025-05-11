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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.JacksonUtil;

class JsonRpcResponseFactoryTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    // Test for ofSuccess(JsonNode result, @Nullable Object id)
    @Test
    void ofSuccess_delegatesToJsonRpcResponse() {
        // Inputs/Preconditions
        final JsonNode resultNode = mapper.getNodeFactory().textNode("data");
        final Object id = "id1";

        // Expected result from direct call
        final JsonRpcResponse expectedResponse = JsonRpcResponse.ofSuccess(resultNode, id);

        // Execute factory call
        final JsonRpcResponse actualResponse = JsonRpcResponseFactory.ofSuccess(resultNode, id);

        // Expected Outcomes/Postconditions
        assertEquals(expectedResponse.jsonRpcVersion(), actualResponse.jsonRpcVersion());
        assertEquals(expectedResponse.result(), actualResponse.result());
        assertEquals(expectedResponse.error(), actualResponse.error());
        assertEquals(expectedResponse.id(), actualResponse.id());
    }

    // Test for ofError(JsonRpcError error, @Nullable Object id)
    @Test
    void ofError_delegatesToJsonRpcResponse() {
        // Inputs/Preconditions
        final JsonRpcError error = JsonRpcError.internalError(null);
        final Object id = "id2";

        // Expected result from direct call
        final JsonRpcResponse expectedResponse = JsonRpcResponse.ofError(error, id);

        // Execute factory call
        final JsonRpcResponse actualResponse = JsonRpcResponseFactory.ofError(error, id);

        // Expected Outcomes/Postconditions
        assertEquals(expectedResponse.jsonRpcVersion(), actualResponse.jsonRpcVersion());
        assertEquals(expectedResponse.result(), actualResponse.result());
        assertEquals(expectedResponse.error(), actualResponse.error());
        assertEquals(expectedResponse.id(), actualResponse.id());
    }

    @Test
    void toHttpResponse_successfulRpcResponse_returnsHttp200Ok() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonNode resultData = mapper.getNodeFactory().booleanNode(true);
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofSuccess(resultData, "req-id-http");
        final Object requestId = "req-id-http";

        // Execute
        final HttpResponse httpResponse = JsonRpcResponseFactory.toHttpResponse(rpcResponse, mapper, requestId);

        // Expected Outcomes/Postconditions
        final AggregatedHttpResponse aggregatedRes = httpResponse.aggregate().join();
        assertEquals(HttpStatus.OK, aggregatedRes.status());
        assertEquals(MediaType.JSON_UTF_8, aggregatedRes.contentType());

        final com.fasterxml.jackson.databind.node.ObjectNode expectedJsonBody = mapper.createObjectNode();
        expectedJsonBody.put("jsonrpc", "2.0");
        expectedJsonBody.set("result", resultData);
        expectedJsonBody.put("id", "req-id-http");
        final String expectedJsonString = mapper.writeValueAsString(expectedJsonBody);

        assertEquals(expectedJsonString, aggregatedRes.contentUtf8());
    }

    @Test
    void toHttpResponse_errorRpcResponse_returnsHttp200OkWithJsonErrorBody() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcError rpcError = JsonRpcError.methodNotFound(null);
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(rpcError, "req-id-err-http");
        final Object requestId = "req-id-err-h_ttp";

        // Execute
        final HttpResponse httpResponse = JsonRpcResponseFactory.toHttpResponse(rpcResponse, mapper, requestId);

        // Expected Outcomes/Postconditions
        final AggregatedHttpResponse aggregatedRes = httpResponse.aggregate().join();
        assertEquals(HttpStatus.OK, aggregatedRes.status());
        assertEquals(MediaType.JSON_UTF_8, aggregatedRes.contentType());

        final com.fasterxml.jackson.databind.node.ObjectNode expectedJsonBody = mapper.createObjectNode();
        expectedJsonBody.put("jsonrpc", "2.0");
        final com.fasterxml.jackson.databind.node.ObjectNode errorNode = mapper.createObjectNode();
        errorNode.put("code", rpcError.code());
        errorNode.put("message", rpcError.message());
        expectedJsonBody.set("error", errorNode);
        expectedJsonBody.put("id", "req-id-err-http");
        final String expectedJsonString = mapper.writeValueAsString(expectedJsonBody);

        assertEquals(expectedJsonString, aggregatedRes.contentUtf8());
    }

    @Test
    void toHttpResponse_serializationFailure_returnsHttp500() throws JsonProcessingException {
        // Inputs/Preconditions
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofSuccess("data", "req-id-ser-fail");
        final Object requestId = "req-id-ser-fail";

        final ObjectMapper mockMapper = mock(ObjectMapper.class);
        // Use a more specific type for the anonymous class if possible, or ensure the method signature matches.
        // For writeValueAsString(Object), any Object will do for the argument matcher.
        when(mockMapper.writeValueAsString(any(JsonRpcResponse.class)))
                .thenThrow(new JsonProcessingException("Serialization failed") {});

        // Execute
        final HttpResponse httpResponse =
                JsonRpcResponseFactory.toHttpResponse(rpcResponse, mockMapper, requestId);

        // Expected Outcomes/Postconditions
        final AggregatedHttpResponse aggregatedRes = httpResponse.aggregate().join();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, aggregatedRes.status());
        assertEquals(MediaType.PLAIN_TEXT_UTF_8, aggregatedRes.contentType());
        assertEquals("Internal Server Error: Failed to serialize JSON-RPC response.",
                                aggregatedRes.contentUtf8());
    }

    // Tests for fromThrowable(Throwable throwable, @Nullable Object id, String methodName)
    @Test
    void fromThrowable_JsonProcessingException_mapsToInternalError() {
        // Inputs/Preconditions
        final Throwable throwable = new JsonProcessingException("parse error detail") {};
        final Object id = "throwable-id-1";
        final String methodName = "methodCausingJsonError";

        // Execute
        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code(), response.error().code());
        // The message from JsonRpcResponseFactory.fromThrowable might be more specific
        assertThat(response.error().message())
                .isEqualTo("Internal error");
    }

    @Test
    void fromThrowable_IllegalArgumentException_mapsToInvalidRequest() {
        // Inputs/Preconditions
        final Throwable throwable = new IllegalArgumentException("Bad argument provided");
        final Object id = "throwable-id-2";
        final String methodName = "methodWithBadArg";

        // Execute
        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Invalid Request");
    }

    @Test
    void fromThrowable_OtherException_mapsToInternalError() {
        // Inputs/Preconditions
        final Throwable throwable = new RuntimeException("Generic runtime issue");
        final Object id = "throwable-id-3";
        final String methodName = "genericErrorMethod";

        // Execute
        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Internal error");
    }

    @Test
    void fromThrowable_CompletionException_unwrapsAndMapsCause() {
        // Inputs/Preconditions
        final Throwable cause = new IllegalArgumentException("Wrapped bad argument");
        final Throwable throwable = new CompletionException(cause);
        final Object id = "throwable-id-4";
        final String methodName = "wrappedErrorMethod";

        // Execute
        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        // Expected Outcomes/Postconditions
        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        // Should be mapped based on the cause (IllegalArgumentException -> INVALID_REQUEST)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Invalid Request");
    }
}
