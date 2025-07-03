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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.sse.ServerSentEvent;
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

    private static final JsonRpcService jsonRpcService = JsonRpcService.builder()
            .addHandler("echo", new EchoHandler())
            .addHandler("fail", new FailingHandler())
            .build();

    private static final JsonRpcService sseJsonRpcService = JsonRpcService.builder()
            .addHandler("echo", new EchoHandler())
            .useSse(true)
            .build();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/json-rpc", jsonRpcService);
            sb.service("/json-rpc-sse", sseJsonRpcService);
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
        final JsonRpcRequest expectedRequest = JsonRpcRequest.of(jsonNode);

        assertThat(actualRequest).isEqualTo(expectedRequest);
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
    void buildFinalResponse_whenNotificationWithResult_thenReturnsInternalError() {
        final JsonRpcRequest notificationRequest = JsonRpcRequest.of(null, "hello");
        final JsonRpcResponse resultResponse = JsonRpcResponse.of("world");

        final DefaultJsonRpcResponse actualResponse =
                jsonRpcService.buildFinalResponse(notificationRequest, resultResponse);
        final DefaultJsonRpcResponse expectedResponse =
                new DefaultJsonRpcResponse(notificationRequest.id(),
                        JsonRpcError.INTERNAL_ERROR
                                .withData("Non-error responses can't have nulls in the Id field."));

        assertThat(actualResponse).isEqualTo(expectedResponse);
    }

    @Test
    void toServerSentEvent_whenValidResponse_thenConvertsSuccessfully() throws JsonProcessingException {
        final DefaultJsonRpcResponse jsonRpcResponse = new DefaultJsonRpcResponse(1, "hello");

        final ServerSentEvent actualServerSentEvent = JsonRpcService.toServerSentEvent(jsonRpcResponse);
        final ServerSentEvent expectedServerSentEvent =
                ServerSentEvent.ofData(objectMapper.writeValueAsString(jsonRpcResponse));

        assertThat(actualServerSentEvent).isEqualTo(expectedServerSentEvent);
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
    void invokeMethod_whenValidRequest_thenSucceeds() {
        final JsonRpcRequest validRequest = JsonRpcRequest.of(1, "echo", "hello");

        final DefaultJsonRpcResponse actualResponse = jsonRpcService.invokeMethod(null, validRequest).join();
        final DefaultJsonRpcResponse expectedResponse =
                new DefaultJsonRpcResponse(1, ImmutableList.of("hello"));

        assertThat(actualResponse).isEqualTo(expectedResponse);
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
    void executeRpcCall_whenValidRequest_thenSucceeds() {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(1, "echo", "hello"));

        final DefaultJsonRpcResponse actualResponse = jsonRpcService.executeRpcCall(null, requestNode).join();
        final DefaultJsonRpcResponse expectedResponse =
                new DefaultJsonRpcResponse(1, ImmutableList.of("hello"));

        assertThat(actualResponse).isEqualTo(expectedResponse);
    }

    @Test
    void executeRpcCall_whenHandlerThrowsException_thenReturnsInternalError() {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(1, "fail"));

        final DefaultJsonRpcResponse actualResponse = jsonRpcService.executeRpcCall(null, requestNode).join();

        assertThat(actualResponse.id()).isEqualTo(1);
        assertThat(actualResponse.error().code()).isEqualTo(JsonRpcError.INTERNAL_ERROR.code());
        assertThat(actualResponse.error().message()).isEqualTo(JsonRpcError.INTERNAL_ERROR.message());
        assertThat(actualResponse.error().data().toString()).contains("error");
    }

    @Test
    void dispatchRequest_whenUnaryRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(1, "echo", "hello"));

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(null, requestNode)
                        .aggregate().join();

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final JsonNode expectedNode =
                objectMapper.valueToTree(new DefaultJsonRpcResponse(1, ImmutableList.of("hello")));

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void dispatchRequest_whenNotificationRequest_thenReturnsNoContent() throws JsonProcessingException {
        final JsonNode requestNode = objectMapper.valueToTree(JsonRpcRequest.of(null, "echo", "hello"));

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(null, requestNode)
                        .aggregate().join();

        assertThat(aggregatedHttpResponse.contentUtf8()).isEmpty();
    }

    @Test
    void dispatchRequest_whenBatchRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final List<JsonRpcRequest> jsonRpcRequests = Arrays.asList(
                JsonRpcRequest.of(1, "echo", "hello"),
                JsonRpcRequest.of(2, "echo", Arrays.asList(1, 2)),
                JsonRpcRequest.of(null, "echo", "hello")
        );

        final JsonNode requestNode = objectMapper.valueToTree(jsonRpcRequests);

        final AggregatedHttpResponse aggregatedHttpResponse =
                jsonRpcService.dispatchRequest(null, requestNode)
                        .aggregate().join();

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());

        final List<DefaultJsonRpcResponse> expectedResponses = Arrays.asList(
                new DefaultJsonRpcResponse(1, ImmutableList.of("hello")),
                new DefaultJsonRpcResponse(2, Arrays.asList(1, 2))
                // Notification
        );
        final JsonNode expectedNode = objectMapper.valueToTree(expectedResponses);

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void dispatchRequest_whenBatchRequestWithSse_thenHandlesSuccessfully() throws JsonProcessingException {
        final List<JsonRpcRequest> jsonRpcRequests = Arrays.asList(
                JsonRpcRequest.of(1, "echo", "hello"),
                JsonRpcRequest.of(2, "echo", Arrays.asList(1, 2)),
                JsonRpcRequest.of(null, "echo", "hello"));

        final JsonNode requestNode = objectMapper.valueToTree(jsonRpcRequests);

        final HttpResponse httpResponse = sseJsonRpcService.dispatchRequest(null, requestNode);

        final List<HttpObject> httpObjects = new ArrayList<>();
        final CompletableFuture<Void> completionFuture = httpResponse.peek(httpObjects::add).subscribe();
        completionFuture.join();

        final Iterator<HttpObject> httpObjectIterator = httpObjects.iterator();

        final HttpHeaders httpHeaders = (HttpHeaders) httpObjectIterator.next();
        assertThat(httpHeaders.contentType()).isEqualTo(MediaType.EVENT_STREAM);

        final String actualSseData1 = ((HttpData) httpObjectIterator.next()).toStringUtf8();
        final String expectedSseJson1 =
                objectMapper.writeValueAsString(new DefaultJsonRpcResponse(1, ImmutableList.of("hello")));
        assertThat(actualSseData1).contains(expectedSseJson1);

        final String actualSseData2 = ((HttpData) httpObjectIterator.next()).toStringUtf8();
        final String expectedSseJson2 =
                objectMapper.writeValueAsString(new DefaultJsonRpcResponse(2, Arrays.asList(1, 2)));
        assertThat(actualSseData2).contains(expectedSseJson2);
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
        assertThat(aggregatedHttpResponse.content().toStringUtf8()).contains("Invalid JSON format");
    }

    @Test
    void serve_whenUnaryRequest_thenHandlesSuccessfully() throws JsonProcessingException {
        final JsonRpcRequest request = JsonRpcRequest.of(1, "echo", "hello");
        final AggregatedHttpResponse aggregatedHttpResponse = client().execute(
                HttpRequest.builder()
                           .post("/json-rpc")
                           .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(request))
                           .build())
                .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.OK);

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final JsonNode expectedNode =
                objectMapper.valueToTree(new DefaultJsonRpcResponse(1, ImmutableList.of("hello")));

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

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEmpty();
    }

    @Test
    void serve_whenBatchRequest_thenHandlesSuccessfully() throws JsonMappingException, JsonProcessingException {
        final List<JsonRpcRequest> jsonRpcRequests = Arrays.asList(
                JsonRpcRequest.of(1, "echo", "hello"),
                JsonRpcRequest.of(2, "echo", Arrays.asList(1, 2)),
                JsonRpcRequest.of(null, "echo", "hello"));

        final AggregatedHttpResponse aggregatedHttpResponse = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(jsonRpcRequests))
                        .build())
                .aggregate().join();

        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.OK);

        final JsonNode actualNode = objectMapper.readTree(aggregatedHttpResponse.contentUtf8());
        final List<DefaultJsonRpcResponse> expectedResponses = Arrays.asList(
                new DefaultJsonRpcResponse(1, ImmutableList.of("hello")),
                new DefaultJsonRpcResponse(2, Arrays.asList(1, 2))
                // Notification
        );
        final JsonNode expectedNode = objectMapper.valueToTree(expectedResponses);

        assertThat(actualNode).isEqualTo(expectedNode);
    }

    @Test
    void serve_whenBatchRequestWithSse_thenHandlesSuccessfully() throws JsonProcessingException {
        final List<JsonRpcRequest> jsonRpcRequests = Arrays.asList(
                JsonRpcRequest.of(1, "echo", "hello"),
                JsonRpcRequest.of(2, "echo", Arrays.asList(1, 2)),
                JsonRpcRequest.of(null, "echo", "hello"));

        final HttpResponse httpResponse = client().execute(
                HttpRequest.builder()
                        .post("/json-rpc-sse")
                        .content(MediaType.JSON_UTF_8, objectMapper.writeValueAsString(jsonRpcRequests))
                        .build());

        final List<HttpObject> httpObjects = new ArrayList<>();
        final CompletableFuture<Void> completionFuture = httpResponse.peek(httpObjects::add).subscribe();
        completionFuture.join();

        final Iterator<HttpObject> httpObjectIterator = httpObjects.iterator();

        final HttpHeaders httpHeaders = (HttpHeaders) httpObjectIterator.next();
        assertThat(httpHeaders.contentType()).isEqualTo(MediaType.EVENT_STREAM);

        final String actualSseData1 = ((HttpData) httpObjectIterator.next()).toStringUtf8();
        final String expectedSseJson1 =
                objectMapper.writeValueAsString(new DefaultJsonRpcResponse(1, ImmutableList.of("hello")));
        assertThat(actualSseData1).contains(expectedSseJson1);

        final String actualSseData2 = ((HttpData) httpObjectIterator.next()).toStringUtf8();
        final String expectedSseJson2 =
                objectMapper.writeValueAsString(new DefaultJsonRpcResponse(2, Arrays.asList(1, 2)));
        assertThat(actualSseData2).contains(expectedSseJson2);

        assertThat(httpObjectIterator.next().isEndOfStream()).isTrue();
    }
}
