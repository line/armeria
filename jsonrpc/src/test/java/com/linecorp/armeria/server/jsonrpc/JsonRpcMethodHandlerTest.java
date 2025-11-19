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
package com.linecorp.armeria.server.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcNotification;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@SuppressWarnings("unchecked")
class JsonRpcMethodHandlerTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final class EchoMethodHandler implements JsonRpcMethodHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
            return UnmodifiableFuture.completedFuture(JsonRpcResponse.ofSuccess(request.params()));
        }
    }

    private static final class FailingMethodHandler implements JsonRpcMethodHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
            final CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("fail"));
            return future;
        }
    }

    private static final class ValidatingMethodHandler implements JsonRpcMethodHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
            if (request.params().isEmpty()) {
                return UnmodifiableFuture.completedFuture(
                        JsonRpcResponse.ofFailure(JsonRpcError.INVALID_PARAMS));
            }
            return UnmodifiableFuture.completedFuture(JsonRpcResponse.ofSuccess(request.params()));
        }
    }

    private static final class ErrorEchoMethodHandler implements JsonRpcMethodHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
            final Map<String, Object> jsonError = (Map<String, Object>) request.params().asMap().get("error");

            try {
                final JsonRpcError jsonRpcError = objectMapper.treeToValue(objectMapper.valueToTree(jsonError),
                                                                           JsonRpcError.class);
                return UnmodifiableFuture.completedFuture(
                        JsonRpcResponse.ofFailure(jsonRpcError));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<Void> onNotification(ServiceRequestContext ctx,
                                                      JsonRpcNotification notification) {
            final UnmodifiableFuture<?> res = UnmodifiableFuture.completedFuture(
                    JsonRpcResponse.ofSuccess("hello"));
            //noinspection unchecked
            return (CompletableFuture<Void>) res;
        }
    }

    private static final class ExceptionMethodHandler implements JsonRpcMethodHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
            throw new RuntimeException("exception");
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        final JsonRpcService jsonRpcService =
                JsonRpcService.builder()
                              .methodHandler("echo", new EchoMethodHandler())
                              .methodHandler("errorEcho", new ErrorEchoMethodHandler())
                              .methodHandler("fail", new FailingMethodHandler())
                              .methodHandler("exception", new ExceptionMethodHandler())
                              .methodHandler("validate", new ValidatingMethodHandler())
                              .build();

        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/json-rpc", jsonRpcService);
            sb.requestTimeoutMillis(0);
        }
    };

    private BlockingWebClient client;

    @BeforeEach
    void setUp() {
        client = server.blockingWebClient();
    }

    @ValueSource(strings = {
            "{\"jsonrpc\": \"2.0\", \"method\": \"echo\"]", // Malformed JSON
            "{\"jsonrpc\": \"2.0\", \"id\": 1}", // Missing 'method' field
            "{\"jsonrpc\": \"1.0\", \"id\": 1, \"method\": \"echo\"}" // Unsupported JSON-RPC version
    })
    @ParameterizedTest
    void rejectInvalidJsonInput(String invalidJson) throws JsonProcessingException {
        final AggregatedHttpRequest httpRequest =
                AggregatedHttpRequest.of(HttpMethod.POST,
                                         "/json-rpc",
                                         MediaType.JSON_UTF_8,
                                         HttpData.ofUtf8(invalidJson));

        final AggregatedHttpResponse response = client.execute(httpRequest);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        final JsonRpcResponse rpcResponse = objectMapper.readValue(response.contentUtf8(),
                                                                   JsonRpcResponse.class);
        assertThat(rpcResponse.id()).isNull();
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.PARSE_ERROR.code());
    }

    @Test
    void rejectBatchRequests() throws JsonProcessingException {
        final List<JsonRpcRequest> jsonRpcBatchRequest = ImmutableList.of(
                JsonRpcRequest.of(1, "echo", ImmutableList.of("hello")),
                JsonRpcRequest.of(2, "echo", ImmutableList.of("world")),
                JsonRpcRequest.of(3, "echo", ImmutableList.of("armeria")));
        final ResponseEntity<JsonRpcResponse> response =
                client.prepare()
                      .post("/json-rpc")
                      .contentJson(jsonRpcBatchRequest)
                      .asJson(JsonRpcResponse.class, s -> true)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isNull();
        assertThat(rpcResponse.hasError()).isTrue();
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.INVALID_REQUEST.code());
    }

    @Test
    void successEchoRequest_withParams() throws JsonProcessingException {
        final JsonRpcRequest jsonRpcUnaryRequest = JsonRpcRequest.of(1, "echo", ImmutableList.of("hello"));
        final ResponseEntity<JsonRpcResponse> response = execute(jsonRpcUnaryRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat((List<String>) rpcResponse.result()).containsExactly("hello");
    }

    @Test
    void successEchoRequest_noParams() throws JsonProcessingException {
        final JsonRpcRequest jsonRpcUnaryRequest = JsonRpcRequest.of("my-id", "echo");
        final ResponseEntity<JsonRpcResponse> response = execute(jsonRpcUnaryRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo("my-id");
        assertThat((List<String>) rpcResponse.result()).isEmpty();
    }

    @Test
    void rejectUnknownMethod() throws JsonProcessingException {
        final JsonRpcRequest jsonRpcRequest = JsonRpcRequest.of(1, "hello");
        final ResponseEntity<JsonRpcResponse> response = execute(jsonRpcRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND.code());
    }

    @Test
    void shouldReturnBadRequestForInvalidParams() {
        final JsonRpcRequest jsonRpcRequest = JsonRpcRequest.of(1, "validate");
        final ResponseEntity<JsonRpcResponse> response = execute(jsonRpcRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.INVALID_PARAMS.code());

        final JsonRpcRequest jsonRpcRequest1 = JsonRpcRequest.of(2, "validate", ImmutableList.of(1));
        final ResponseEntity<JsonRpcResponse> response1 = execute(jsonRpcRequest1);

        assertThat(response1.status()).isEqualTo(HttpStatus.OK);
        assertThat(response1.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse1 = response1.content();
        assertThat(rpcResponse1.id()).isEqualTo(2);
        assertThat(rpcResponse1.hasError()).isFalse();
        assertThat((List<Integer>) rpcResponse1.result()).containsExactly(1);
    }

    @ValueSource(ints = {
            -32603, // Internal error
            -32000, // Server error -32000 to -32099
            -32050, // Server error -32000 to -32099
            -32099, // Server error -32000 to -32099
            -40000  // Unknown error
    })
    @ParameterizedTest
    void shouldReturnServerError(int code) {
        final JsonRpcError jsonRpcError = new JsonRpcError(code, "message");
        final JsonRpcRequest jsonRpcRequest = JsonRpcRequest.of(1, "errorEcho",
                                                                ImmutableMap.of("error", jsonRpcError));
        final ResponseEntity<JsonRpcResponse> response = execute(jsonRpcRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.hasContent()).isTrue();
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat(rpcResponse.error().code()).isEqualTo(code);
    }

    @Test
    void notificationShouldReturnNull() {
        final JsonRpcNotification noti = JsonRpcNotification.of("echo", ImmutableList.of("hello"));
        final AggregatedHttpResponse response = client.prepare()
                                                      .post("/json-rpc")
                                                      .contentJson(noti)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.content().isEmpty()).isTrue();
    }

    @Test
    void shouldIgnoreReturnValueOfNotification() {
        final JsonRpcNotification noti = JsonRpcNotification.of("errorEcho", ImmutableList.of("hello"));
        final AggregatedHttpResponse response = client.prepare()
                                                      .post("/json-rpc")
                                                      .contentJson(noti)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.content().isEmpty()).isTrue();
    }

    @Test
    void handleUnknownMethod() {
        final JsonRpcRequest requestWithUnknownMethod = JsonRpcRequest.of(1, "Unknown");

        final ResponseEntity<JsonRpcResponse> response = execute(requestWithUnknownMethod);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND.code());
    }

    @Test
    void shouldHandlePositionalParams() {
        final JsonRpcRequest validRequest = JsonRpcRequest.of(1, "echo", ImmutableList.of(1, 2, 3, 4));
        final ResponseEntity<JsonRpcResponse> response = execute(validRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat((List<Integer>) rpcResponse.result()).isEqualTo(validRequest.params().asList());
    }

    @Test
    void shouldHandleNamedParams() throws JsonProcessingException {
        final JsonRpcRequest validRequest = JsonRpcRequest.of("2", "echo", ImmutableMap.of("subtrahend", 23));
        final ResponseEntity<JsonRpcResponse> response = execute(validRequest);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo("2");
        assertThat((Map<String, Object>) rpcResponse.result()).isEqualTo(validRequest.params().asMap());
    }

    @ValueSource(strings = { "fail", "exception" })
    @ParameterizedTest
    void exceptionHandling(String methodName) {
        final JsonRpcRequest request = JsonRpcRequest.of(10, methodName);
        final ResponseEntity<JsonRpcResponse> response = execute(request);

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(10);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.INTERNAL_ERROR.code());
        assertThat(rpcResponse.error().data()).isEqualTo(methodName);
    }

    @Test
    void disallowGetMethod() throws JsonProcessingException {
        final AggregatedHttpResponse response = client.prepare()
                                                      .get("/json-rpc")
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void shouldHandleUnsupportedMediaType() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(1, "echo");

        final AggregatedHttpResponse response = client.prepare()
                                                      .post("/json-rpc")
                                                      .content(MediaType.PLAIN_TEXT,
                                                               objectMapper.writeValueAsString(request))
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private ResponseEntity<JsonRpcResponse> execute(JsonRpcMessage request) {
        return client.prepare()
                     .post("/json-rpc")
                     .contentJson(request)
                     .asJson(JsonRpcResponse.class, status -> true)
                     .execute();
    }
}
