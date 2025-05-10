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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.JacksonUtil;

class JsonRpcResponseFactoryTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final JsonNode SUCCESS_RESULT = new TextNode("success_data");
    private static final JsonRpcError TEST_ERROR = new JsonRpcError(-32000, "Test Server Error");

    @Test
    void toAggregatedHttpResponse_success() throws JsonProcessingException {
        final JsonRpcResponse rpcSuccess = JsonRpcResponse.ofSuccess(SUCCESS_RESULT, 1);

        final AggregatedHttpResponse aggregated =
                JsonRpcResponseFactory.toHttpResponse(rpcSuccess, mapper, 1)
                                      .aggregate()
                                      .join();

        final String expectedBody = mapper.writeValueAsString(rpcSuccess);

        assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(aggregated.contentUtf8()).isEqualTo(expectedBody);
    }

    @Test
    void toAggregatedHttpResponse_error() throws JsonProcessingException {
        final JsonRpcResponse rpcError = JsonRpcResponse.ofError(TEST_ERROR, "err-id");

        final AggregatedHttpResponse aggregated =
                JsonRpcResponseFactory.toHttpResponse(rpcError, mapper, "err-id")
                                      .aggregate()
                                      .join();

        final String expectedBody = mapper.writeValueAsString(rpcError);

        assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregated.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(aggregated.contentUtf8()).isEqualTo(expectedBody);
    }

    @Test
    void toAggregatedHttpResponse_serializationFailure() throws JsonProcessingException {
        final JsonRpcResponse rpcSuccess = JsonRpcResponse.ofSuccess(SUCCESS_RESULT, 1);
        final ObjectMapper mockMapper = mock(ObjectMapper.class);
        when(mockMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("Serialization failed") {});

        final AggregatedHttpResponse aggregated =
                JsonRpcResponseFactory.toHttpResponse(rpcSuccess, mockMapper, 1)
                                      .aggregate()
                                      .join();

        assertThat(aggregated.headers().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(aggregated.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(aggregated.contentUtf8())
                .contains("Internal Server Error: Failed to serialize JSON-RPC response.");
    }

    // --- toHttpResponseFuture Tests ---

    @Test
    void toAggregatedHttpResponseFuture() throws JsonProcessingException {
        final JsonRpcResponse rpcSuccess = JsonRpcResponse.ofSuccess(SUCCESS_RESULT, 1);

        final AggregatedHttpResponse expectedAggregated =
                JsonRpcResponseFactory.toHttpResponse(rpcSuccess, mapper, 1)
                                      .aggregate()
                                      .join();

        final AggregatedHttpResponse actualAggregated =
                JsonRpcResponseFactory.toHttpResponse(rpcSuccess, mapper, 1)
                                      .aggregate()
                                      .join();

        assertThat(actualAggregated.headers().status()).isEqualTo(expectedAggregated.headers().status());
        assertThat(actualAggregated.headers().contentType())
                .isEqualTo(expectedAggregated.headers().contentType());
        assertThat(actualAggregated.contentUtf8()).isEqualTo(expectedAggregated.contentUtf8());
    }

    // --- fromThrowable Tests ---

    @Test
    void fromThrowable_jsonProcessingException() {
        final JsonProcessingException exception = new JsonProcessingException("Bad JSON") {};

        final JsonRpcResponse rpcResponse =
                JsonRpcResponseFactory.fromThrowable(exception, "id1", "method1");

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo("id1");
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.message());
    }

    @Test
    void fromThrowable_illegalArgumentException() {
        final IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

        final JsonRpcResponse rpcResponse =
                JsonRpcResponseFactory.fromThrowable(exception, 2, "method2");

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo(2);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.message());
    }

    @Test
    void fromThrowable_completionExceptionWrappingIllegalArgument() {
        final IllegalArgumentException cause = new IllegalArgumentException("Inner invalid arg");

        final CompletionException exception = new CompletionException(cause);

        final JsonRpcResponse rpcResponse =
                JsonRpcResponseFactory.fromThrowable(exception, "id-wrap", "methodWrap");

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo("id-wrap");
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.message());
    }

    @Test
    void fromThrowable_genericException() {
        final RuntimeException exception = new RuntimeException("Something unexpected");

        final JsonRpcResponse rpcResponse = JsonRpcResponseFactory.fromThrowable(
                exception, null, "genericMethod");

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isNull();
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.message());
    }
}
