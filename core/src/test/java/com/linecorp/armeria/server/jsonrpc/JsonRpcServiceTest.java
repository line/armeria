/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcParameter;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonRpcServiceTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final class EchoHandler implements JsonRpcHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> handle(ServiceRequestContext ctx, JsonRpcRequest request) {
            return UnmodifiableFuture.completedFuture(JsonRpcResponse.of(request.params()));
        }
    }

    private static final class FailingHandler implements JsonRpcHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> handle(ServiceRequestContext ctx, JsonRpcRequest request) {
            final CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("error"));
            return future;
        }
    }

    private static final class ExceptionHandler implements JsonRpcHandler {
        @Override
        public CompletableFuture<JsonRpcResponse> handle(ServiceRequestContext ctx, JsonRpcRequest request) {
            throw new RuntimeException("error");
        }
    }

    private static final JsonRpcService jsonRpcService = JsonRpcService.builder()
            .addHandler("echo", new EchoHandler())
            .addHandler("fail", new FailingHandler())
            .addHandler("exception", new ExceptionHandler())
            .build();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/json-rpc", jsonRpcService);
            sb.requestTimeoutMillis(0);
        }
    };

    private WebClient client() {
        return WebClient.builder(server.httpUri())
                .responseTimeoutMillis(0)
                .build();
    }

    @Test
    void parseRequestContentAsJson_whenInvalidJson_thenThrowsException() {
        final String invalidJsonRequest = "{\"jsonrpc\": \"2.0\", \"method\": \"echo\"]";
        final AggregatedHttpRequest httpRequest =
                AggregatedHttpRequest.of(HttpMethod.POST,
                        "/json-rpc",
                        MediaType.JSON_UTF_8,
                        HttpData.ofUtf8(invalidJsonRequest));

        assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcService.parseRequestContentAsJson(httpRequest);
        });
    }

    @Test
    void parseRequestContentAsJson_whenUnaryRequest_thenSucceeds() throws JsonProcessingException {
        final JsonRpcRequest jsonRpcUnaryRequest = JsonRpcRequest.of(1, "echo", "hello");
        final String jsonRpcRequestString = objectMapper.writeValueAsString(jsonRpcUnaryRequest);
        final AggregatedHttpRequest aggregatedHttpRequest =
                AggregatedHttpRequest.of(HttpMethod.POST,
                        "/json-rpc",
                        MediaType.JSON_UTF_8,
                        HttpData.ofUtf8(jsonRpcRequestString));

        final JsonNode actualJsonNode = JsonRpcService.parseRequestContentAsJson(aggregatedHttpRequest);
        final JsonNode expectedJsonNode = objectMapper.readTree(jsonRpcRequestString);

        assertThat(actualJsonNode).isEqualTo(expectedJsonNode);
    }

    @Test
    void parseRequestContentAsJson_whenBatchRequest_thenSucceeds() throws JsonProcessingException {
        final List<JsonRpcRequest> jsonRpcBatchRequest = Arrays.asList(
                JsonRpcRequest.of(1, "echo", "hello"),
                JsonRpcRequest.of(2, "echo", "world"),
                JsonRpcRequest.of(3, "echo", "armeria"));
        final String jsonRpcRequestString = objectMapper.writeValueAsString(jsonRpcBatchRequest);
        final AggregatedHttpRequest aggregatedHttpRequest =
                AggregatedHttpRequest.of(HttpMethod.POST,
                        "/json-rpc",
                        MediaType.JSON_UTF_8,
                        HttpData.ofUtf8(jsonRpcRequestString));

        final JsonNode actualJsonNode = JsonRpcService.parseRequestContentAsJson(aggregatedHttpRequest);
        final JsonNode expectedJsonNode = objectMapper.readTree(jsonRpcRequestString);

        assertThat(actualJsonNode).isEqualTo(expectedJsonNode);
    }

    @Test
    void parseNodeAsRpcRequest_whenMissingMethod_thenThrowsException() throws JsonProcessingException {
        final String invalidJsonString = "{\"jsonrpc\": \"2.0\", \"id\": 1}";
        final JsonNode jsonNode = objectMapper.readTree(invalidJsonString);

        assertThrows(IllegalArgumentException.class, () -> {
            JsonRpcService.parseNodeAsRpcRequest(jsonNode);
        });
    }

    @Test
    void parseNodeAsRpcRequest_whenValidNode_thenSucceeds() throws JsonProcessingException {
        final JsonRpcRequest jsonRpcRequest = JsonRpcRequest.of(1, "hello");
        final JsonNode jsonNode = objectMapper.valueToTree(jsonRpcRequest);
        final JsonRpcRequest actualRequest = JsonRpcService.parseNodeAsRpcRequest(jsonNode);

        assertThat(actualRequest.id()).isEqualTo(jsonRpcRequest.id());
        assertThat(actualRequest.method()).isEqualTo(jsonRpcRequest.method());
        assertThat(actualRequest.params().asList()).isEmpty();
        assertThat(actualRequest.version()).isEqualTo(jsonRpcRequest.version());
    }

    @Test
    void buildFinalResponse_whenResultResponse_thenSucceeds() {
        final JsonRpcRequest jsonRpcRequest = JsonRpcRequest.of(1, "hello");
        final JsonRpcResponse jsonRpcResponse = JsonRpcResponse.of("world");

        final DefaultJsonRpcResponse actualJsonRpcResponse =
                jsonRpcService.buildFinalResponse(jsonRpcRequest, jsonRpcResponse);
        final DefaultJsonRpcResponse expectedJsonRpcResponse =
                new DefaultJsonRpcResponse(1, "world");

        assertThat(actualJsonRpcResponse).isEqualTo(expectedJsonRpcResponse);
    }

    @Test
    void buildFinalResponse_whenErrorResponse_thenSucceeds() {
        final JsonRpcRequest jsonRpcRequest = JsonRpcRequest.of(1, "hello");
        final JsonRpcResponse jsonRpcResponse = JsonRpcResponse.ofError(JsonRpcError.INVALID_PARAMS);

        final DefaultJsonRpcResponse actualJsonRpcResponse =
                jsonRpcService.buildFinalResponse(jsonRpcRequest, jsonRpcResponse);
        final DefaultJsonRpcResponse expectedJsonRpcResponse =
                new DefaultJsonRpcResponse(1, JsonRpcError.INVALID_PARAMS);

        assertThat(actualJsonRpcResponse).isEqualTo(expectedJsonRpcResponse);
    }

    @Test
    void buildFinalResponse_whenInvalidResult_thenReturnsInternalError() {
        final JsonRpcRequest request = JsonRpcRequest.of(1, "hello");
        final JsonRpcResponse invalidResponse = new JsonRpcResponse() {
            @Override
            public Object result() {
                return "result";
            }

            @Override
            public JsonRpcError error() {
                return JsonRpcError.INTERNAL_ERROR;
            }
        };

        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.buildFinalResponse(request, invalidResponse);
        final DefaultJsonRpcResponse expectedResponse =
                new DefaultJsonRpcResponse(request.id(), JsonRpcError.INTERNAL_ERROR
                        .withData("A response cannot have both or neither 'result' and 'error' fields."));

        assertThat(actualResponse).isEqualTo(expectedResponse);
    }

    @Test
    void invokeMethod_whenNotificationRequest_thenReturnsNull() {
        final JsonRpcRequest notificationRequest = JsonRpcRequest.of(null, "echo", "hello");
        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.invokeMethod(null, notificationRequest).join();

        assertThat(actualResponse).isNull();
    }

    @Test
    void invokeMethod_whenHandlerNotFound_thenReturnsMethodNotFoundError() {
        final JsonRpcRequest requestWithUnknownMethod = JsonRpcRequest.of(1, "Unknown", "hello");

        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.invokeMethod(null, requestWithUnknownMethod).join();
        final DefaultJsonRpcResponse expectedResponse =
                new DefaultJsonRpcResponse(1, JsonRpcError.METHOD_NOT_FOUND);

        assertThat(actualResponse).isEqualTo(expectedResponse);
    }

    @Test
    void invokeMethod_whenPosParams_thenSucceeds() {
        final JsonRpcRequest validRequest = JsonRpcRequest.of(1, "echo", 1, 2, 3, 4);
        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.invokeMethod(newCtx(), validRequest).join();

        assertThat(actualResponse.id()).isEqualTo(validRequest.id());
        assertThat(actualResponse.result()).isInstanceOf(JsonRpcParameter.class);
        assertTrue(((JsonRpcParameter) actualResponse.result()).isPositional());
        assertThat(((JsonRpcParameter) actualResponse.result()).asList()).containsExactly(1, 2, 3, 4);
        assertThat(actualResponse.version()).isEqualTo(validRequest.version());
    }

    @Test
    void invokeMethod_whenNamedParams_thenSucceeds() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"echo\", \"params\": {\"subtrahend\": 23}, \"id\": 3}";
        final JsonNode node = objectMapper.readTree(json);
        final JsonRpcRequest validRequest = JsonRpcRequest.of(node);
        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.invokeMethod(newCtx(), validRequest).join();

        assertThat(actualResponse.id()).isEqualTo(validRequest.id());
        assertThat(actualResponse.result()).isInstanceOf(JsonRpcParameter.class);
        assertTrue(((JsonRpcParameter) actualResponse.result()).isNamed());
        assertThat(((JsonRpcParameter) actualResponse.result()).asMap()).containsEntry("subtrahend", 23);
        assertThat(actualResponse.version()).isEqualTo(validRequest.version());
    }

    @Test
    void invokeMethod_whenHandlerThrowsException_thenThrowsException() {
        final JsonRpcRequest requestToFailingHandler = JsonRpcRequest.of(1, "fail");

        assertThrows(RuntimeException.class, () -> {
            jsonRpcService.invokeMethod(null, requestToFailingHandler).join();
        });
    }

    @Test
    void executeRpcCall_whenParseError_thenReturnsParseError() throws JsonProcessingException {
        final String jsonWithMissingMethod = "{\"jsonrpc\": \"2.0\", \"id\": 1}";
        final JsonNode jsonNode = objectMapper.readTree(jsonWithMissingMethod);

        final DefaultJsonRpcResponse actualResponse = jsonRpcService.executeRpcCall(null, jsonNode).join();

        assertThat(actualResponse.id()).isNull();
        assertThat(actualResponse.error().code()).isEqualTo(JsonRpcError.PARSE_ERROR.code());
        assertThat(actualResponse.error().message()).isEqualTo(JsonRpcError.PARSE_ERROR.message());
    }

    @Test
    void executeRpcCall_whenHandlerThrowsException_thenReturnsInternalError() {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(1, "fail"));

        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.executeRpcCall(newCtx(), requestNode).join();

        assertThat(actualResponse.id()).isEqualTo(1);
        assertThat(actualResponse.error().code()).isEqualTo(JsonRpcError.INTERNAL_ERROR.code());
        assertThat(actualResponse.error().message()).isEqualTo(JsonRpcError.INTERNAL_ERROR.message());
        assertThat(actualResponse.error().data().toString()).contains("error");
    }

    @Test
    void dispatchRequest_whenUnaryPosRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(1, "echo", 1, 2, 3, 4));

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(newCtx(), requestNode)
                        .aggregate().join();

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final JsonNode expectedNode =
                objectMapper.valueToTree(new DefaultJsonRpcResponse(1, ImmutableList.of(1, 2, 3, 4)));

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void dispatchRequest_whenUnaryNamedRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"echo\", \"params\": {\"subtrahend\": 23}, \"id\": 1}";
        final JsonNode requestNode = objectMapper.readTree(json);

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(newCtx(), requestNode)
                        .aggregate().join();

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final JsonNode expectedNode =
                objectMapper.valueToTree(new DefaultJsonRpcResponse(1, ImmutableMap.of("subtrahend", 23)));

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void dispatchRequest_whenNotificationRequest_thenReturnsNoContent() throws JsonProcessingException {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(null, "echo", "hello"));

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(newCtx(), requestNode)
                        .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(aggregatedHttpResponse.contentUtf8()).isEmpty();
    }

    @Test
    void dispatchRequest_whenInvalidNode_thenReturnsBadRequest() {
        final JsonNode invalidJsonNode = objectMapper.createObjectNode().put("test", 1)
                .get("test");

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(null, invalidJsonNode)
                        .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void serve_whenInvalidJsonFormat_thenReturnsBadRequest() {
        final String invalidJson = "{\"jsonrpc\": \"2.0\", \"method\": \"echo\"]";
        final AggregatedHttpResponse aggregatedHttpResponse = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.JSON_UTF_8, invalidJson)
                        .build())
                .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(aggregatedHttpResponse.content().toStringUtf8()).contains(
                objectMapper.valueToTree(JsonRpcError.PARSE_ERROR).toString());
    }

    @Test
    void serve_whenUnaryPosRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(1, "echo", 1, 2, 3, 4);
        final AggregatedHttpResponse aggregatedHttpResponse = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(request))
                        .build())
                .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.OK);

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final JsonNode expectedNode =
                objectMapper.valueToTree(new DefaultJsonRpcResponse(1, ImmutableList.of(1, 2, 3, 4)));

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void serve_whenUnaryNamedRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"echo\", \"params\": {\"subtrahend\": 23}, \"id\": 1}";
        final JsonNode node = objectMapper.readTree(json);
        final JsonRpcRequest request = JsonRpcRequest.of(node);

        final AggregatedHttpResponse aggregatedHttpResponse = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(request))
                        .build())
                .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.OK);

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final JsonNode expectedNode =
                objectMapper.valueToTree(new DefaultJsonRpcResponse(1, ImmutableMap.of("subtrahend", 23)));

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void serve_whenUnaryNotificationRequest_thenReturnsEmptyContent() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(null, "echo", "hello");
        final AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(request))
                        .build())
                .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.contentUtf8()).isEmpty();
    }

    @Test
    void serve_whenGetRequest_thenReturnsMethodNotAllowed() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(null, "echo", "hello");
        final AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .get("/json-rpc")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(request))
                        .build())
                .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void serve_whenUnsupportedMediaType_thenReturnsUnsupportedMediaType() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(null, "echo", "hello");
        final AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.PLAIN_TEXT, objectMapper.writeValueAsString(request))
                        .build())
                .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void serve_whenRequestTriggersException_thenReturnsInternalServerError() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(1, "exception");
        final AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(request))
                        .build())
                .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.contentUtf8()).contains("error");
    }

    private static ServiceRequestContext newCtx() {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                .build();
    }
}
