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

    @Test
    void toHttpResponse_successfulRpcResponse_returnsHttp200Ok() throws JsonProcessingException {
        final JsonNode resultData = mapper.getNodeFactory().booleanNode(true);
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofSuccess(resultData, "req-id-http");
        final Object requestId = "req-id-http";

        final HttpResponse httpResponse = JsonRpcResponseFactory.toHttpResponse(rpcResponse, mapper, requestId);

        final AggregatedHttpResponse aggregatedRes = httpResponse.aggregate().join();
        assertEquals(HttpStatus.OK, aggregatedRes.status());
        assertEquals(MediaType.JSON_UTF_8, aggregatedRes.contentType());

        final com.fasterxml.jackson.databind.node.ObjectNode expectedJsonBody = mapper.createObjectNode();
        expectedJsonBody.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
        expectedJsonBody.set("result", resultData);
        expectedJsonBody.put("id", "req-id-http");
        final String expectedJsonString = mapper.writeValueAsString(expectedJsonBody);

        assertEquals(expectedJsonString, aggregatedRes.contentUtf8());
    }

    @Test
    void toHttpResponse_errorRpcResponse_returnsHttp200OkWithJsonErrorBody() throws JsonProcessingException {
        final JsonRpcError rpcError = JsonRpcError.METHOD_NOT_FOUND;
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(rpcError, "req-id-err-http");
        final Object requestId = "req-id-err-h_ttp";

        final HttpResponse httpResponse = JsonRpcResponseFactory.toHttpResponse(rpcResponse, mapper, requestId);

        final AggregatedHttpResponse aggregatedRes = httpResponse.aggregate().join();
        assertEquals(HttpStatus.OK, aggregatedRes.status());
        assertEquals(MediaType.JSON_UTF_8, aggregatedRes.contentType());

        final com.fasterxml.jackson.databind.node.ObjectNode expectedJsonBody = mapper.createObjectNode();
        expectedJsonBody.put("jsonrpc", JsonRpcUtil.JSON_RPC_VERSION);
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
        final JsonRpcResponse rpcResponse = JsonRpcResponse.ofSuccess("data", "req-id-ser-fail");
        final Object requestId = "req-id-ser-fail";

        final ObjectMapper mockMapper = mock(ObjectMapper.class);
        when(mockMapper.writeValueAsString(any(JsonRpcResponse.class)))
                .thenThrow(new JsonProcessingException("Serialization failed") {});

        final HttpResponse httpResponse =
                JsonRpcResponseFactory.toHttpResponse(rpcResponse, mockMapper, requestId);

        final AggregatedHttpResponse aggregatedRes = httpResponse.aggregate().join();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, aggregatedRes.status());
        assertEquals(MediaType.PLAIN_TEXT_UTF_8, aggregatedRes.contentType());
        assertEquals("Internal Server Error: Failed to serialize JSON-RPC response.",
                aggregatedRes.contentUtf8());
    }

    @Test
    void fromThrowable_JsonProcessingException_mapsToInternalError() {
        final Throwable throwable = new JsonProcessingException("parse error detail") {};
        final Object id = "throwable-id-1";
        final String methodName = "methodCausingJsonError";

        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcError.INTERNAL_ERROR.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Internal error");
    }

    @Test
    void fromThrowable_IllegalArgumentException_mapsToInvalidRequest() {
        final Throwable throwable = new IllegalArgumentException("Bad argument provided");
        final Object id = "throwable-id-2";
        final String methodName = "methodWithBadArg";

        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Invalid Request");
    }

    @Test
    void fromThrowable_OtherException_mapsToInternalError() {
        final Throwable throwable = new RuntimeException("Generic runtime issue");
        final Object id = "throwable-id-3";
        final String methodName = "genericErrorMethod";

        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcError.INTERNAL_ERROR.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Internal error");
    }

    @Test
    void fromThrowable_CompletionException_unwrapsAndMapsCause() {
        final Throwable cause = new IllegalArgumentException("Wrapped bad argument");
        final Throwable throwable = new CompletionException(cause);
        final Object id = "throwable-id-4";
        final String methodName = "wrappedErrorMethod";

        final JsonRpcResponse response = JsonRpcResponseFactory.fromThrowable(throwable, id, methodName);

        assertNotNull(response);
        assertEquals(id, response.id());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), response.error().code());
        assertThat(response.error().message())
                .isEqualTo("Invalid Request");
    }
}
