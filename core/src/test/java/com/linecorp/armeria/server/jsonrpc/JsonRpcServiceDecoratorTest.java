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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonRpcServiceDecoratorTest {

private static final ObjectMapper mapper = new ObjectMapper();

        @RegisterExtension
        static final ServerExtension server = new ServerExtension() {
                @Override
                protected void configure(ServerBuilder sb) {
                        sb.service("/test-echo",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "echo-test");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "echo-test");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                aggregated.contentType(),
                                                                aggregated.content());
                                        })));
                        sb.service("/test-success",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-001");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "testMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"data\": \"success_data\"}");
                                        })));
                        sb.service("/test-error",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-002");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "testMethodError");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"errorCode\": -32000, " +
                                                                        "\"errorMessage\": \"Server error\"}");
                                        })));
                        sb.service("/test-success-string-id",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "string-id-123");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "testMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"data\": \"success_data\"}");
                                        })));
                        sb.service("/test-success-numeric-id",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, 12345);
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "testMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"data\": \"success_data\"}");
                                        })));
                        sb.service("/test-success-null-id",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, null);
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "testMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"data\": \"success_data\"}");
                                        })));
                        sb.service("/test-notification",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "noti-001");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "notifyMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, true);
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"data\": \"success_data\"}");
                                        })));
                        sb.service("/test-no-id-attr",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "someMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.CREATED,
                                                                MediaType.PLAIN_TEXT_UTF_8,
                                                                "Original Response No ID");
                                        })));
                        sb.service("/test-no-isnotification-attr",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-008");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "method008");
                                                return HttpResponse.of(
                                                                HttpStatus.OK,
                                                                MediaType.APPLICATION_XML_UTF_8,
                                                                "<data>text</data>");
                                        })));
                        sb.service("/test-no-method-attr",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-009");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                final ResponseHeaders responseHeaders =
                                                                ResponseHeaders.of(
                                                                                HttpStatus.NOT_FOUND,
                                                                                HttpHeaderNames.CONTENT_TYPE,
                                                                                MediaType.HTML_UTF_8);
                                                return HttpResponse.of(
                                                                responseHeaders,
                                                                HttpData.ofUtf8(
                                                                        "<html><body>Not Found</body></html>"));
                                        })));
                        sb.service("/test-http201",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-011");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "createMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.CREATED,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"resourceId\": \"newResource123\"}");
                                        })));
                        sb.service("/test-http400",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-012");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "invalidMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.BAD_REQUEST,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"error_type\": \"VALIDATION_ERROR\"," +
                                                                        " \"details\": \"Invalid parameter\"}");
                                        })));
                        sb.service("/test-http503",
                                        (ctx, req) -> HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                                                ctx.setAttr(JsonRpcAttributes.ID, "req-013");
                                                ctx.setAttr(JsonRpcAttributes.METHOD, "unavailableMethod");
                                                ctx.setAttr(JsonRpcAttributes.IS_NOTIFICATION, false);
                                                return HttpResponse.of(
                                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                                MediaType.JSON_UTF_8,
                                                                "{\"reason\": \"Downstream timeout\"}");
                                        })));
                        sb.decorator(JsonRpcServiceDecorator::new);
                        sb.requestTimeoutMillis(0);
                }
        };

        private WebClient client() {
                return WebClient.builder(server.httpUri())
                                .responseTimeoutMillis(0)
                                .build();
        }

        @Test
        void testNonNotificationRequest_ExistingGeneralCase() throws JsonProcessingException {
                final JsonNode requestBody = mapper.createObjectNode()
                                .put("test", "test_value_existing");

                final JsonRpcResponse expected = JsonRpcResponse.ofSuccess(requestBody, "echo-test");

                final AggregatedHttpResponse response =
                                client().execute(
                                                HttpRequest.of(HttpMethod.POST,
                                                                "/test-echo",
                                                                MediaType.JSON_UTF_8,
                                                                mapper.writeValueAsString(requestBody)))
                                                .aggregate().join();

                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());

                final JsonNode expectedJson = mapper.valueToTree(expected);
                final JsonNode actualJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedJson, actualJson);
        }

        @Test
        void nonNotificationRequest_DelegatedServiceReturnsSuccessJson() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"p1\", \"p2\"]}";
                final JsonNode expectedResultNode = mapper.readTree("{\"data\": \"success_data\"}");
                final JsonRpcResponse expectedRpcResponse =
                                JsonRpcResponse.ofSuccess(expectedResultNode, "req-001");
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(HttpMethod.POST, "/test-success", MediaType.JSON_UTF_8,
                                                requestJsonBody))
                                .aggregate().join();

                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void nonNotificationRequest_DelegatedServiceReturnsErrorJson() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"err_p1\"]}";
                final String expectedErrorDataString =
                                "Internal server error during delegate execution " +
                                                "for method 'testMethodError' " +
                                                "(delegate returned 500 Internal Server Error): " +
                                                "{\"errorCode\": -32000, \"errorMessage\": \"Server error\"}";
                final JsonRpcError jsonRpcError = JsonRpcError.INTERNAL_ERROR
                                .withData(mapper.getNodeFactory().textNode(expectedErrorDataString));
                final JsonRpcResponse expectedRpcResponse = JsonRpcResponse.ofError(jsonRpcError, "req-002");
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(HttpMethod.POST, "/test-error", MediaType.JSON_UTF_8,
                                                requestJsonBody))
                                .aggregate().join();

                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void nonNotificationRequest_StringId() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"p1\", \"p2\"]}";
                final JsonNode expectedResultNode = mapper.readTree("{\"data\": \"success_data\"}");
                final JsonRpcResponse expectedRpcResponse =
                                JsonRpcResponse.ofSuccess(expectedResultNode, "string-id-123");
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-success-string-id",
                                                MediaType.JSON_UTF_8, requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void nonNotificationRequest_NumericId() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"p1\", \"p2\"]}";
                final JsonNode expectedResultNode = mapper.readTree("{\"data\": \"success_data\"}");
                final JsonRpcResponse expectedRpcResponse =
                                JsonRpcResponse.ofSuccess(expectedResultNode,
                                                mapper.getNodeFactory().numberNode(12345));
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-success-numeric-id",
                                                MediaType.JSON_UTF_8, requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void nonNotificationRequest_NullId() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"p1\", \"p2\"]}";
                final JsonNode expectedResultNode = mapper.readTree("{\"data\": \"success_data\"}");
                final JsonNode expectedRpcResponseJson = expectedResultNode;
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-success-null-id",
                                                MediaType.JSON_UTF_8, requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void notificationRequest_ReturnsHttp200OkEmptyBody() throws JsonProcessingException {
                final String requestJsonBody = "{\"method\": \"notifyMethod\", \"params\": [\"p1\"]}";
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-notification",
                                                MediaType.JSON_UTF_8, requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(0, response.content().length());
                final String cl = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
                if (cl != null) {
                        assertEquals("0", cl);
                }
        }

        @Test
        void nonRpcRequest_NoIdAttribute() throws JsonProcessingException {
                final String requestBody = "Any content";
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-no-id-attr",
                                                MediaType.PLAIN_TEXT_UTF_8, requestBody))
                                .aggregate().join();
                assertEquals(HttpStatus.CREATED, response.status());
                assertEquals(MediaType.PLAIN_TEXT_UTF_8, response.contentType());
                assertEquals("Original Response No ID", response.contentUtf8());
        }

        @Test
        void nonRpcRequest_NoIsNotificationAttribute() throws JsonProcessingException {
                final String requestBody = "Any content";
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-no-isnotification-attr",
                                                MediaType.PLAIN_TEXT_UTF_8, requestBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.APPLICATION_XML_UTF_8, response.contentType());
                assertEquals("<data>text</data>", response.contentUtf8());
        }

        @Test
        void nonRpcRequest_NoMethodAttribute() throws JsonProcessingException {
                final String requestBody = "Any content";
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(
                                                HttpMethod.POST,
                                                "/test-no-method-attr",
                                                MediaType.PLAIN_TEXT_UTF_8, requestBody))
                                .aggregate().join();
                assertEquals(HttpStatus.NOT_FOUND, response.status());
                assertEquals(MediaType.HTML_UTF_8, response.contentType());
                assertEquals("<html><body>Not Found</body></html>", response.contentUtf8());
        }

        @Test
        void nonNotificationRequest_DelegatedServiceReturnsHttp201Created() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"create_p1\"]}";
                final JsonNode expectedResultNode =
                                mapper.readTree("{\"resourceId\": \"newResource123\"}");
                final JsonRpcResponse expectedRpcResponse =
                                JsonRpcResponse.ofSuccess(expectedResultNode, "req-011");
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(HttpMethod.POST, "/test-http201", MediaType.JSON_UTF_8,
                                                requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void nonNotificationRequest_DelegatedServiceReturnsHttp400BadRequest() throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"bad_p1\"]}";
                final String expectedErrorDataString =
                                "Invalid parameters for method 'invalidMethod' (delegate returned 400): " +
                                "{\"error_type\": \"VALIDATION_ERROR\", \"details\": \"Invalid parameter\"}";
                final JsonRpcError jsonRpcError = JsonRpcError.INVALID_PARAMS
                                .withData(mapper.getNodeFactory().textNode(expectedErrorDataString));
                final JsonRpcResponse expectedRpcResponse = JsonRpcResponse.ofError(jsonRpcError, "req-012");
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(HttpMethod.POST, "/test-http400", MediaType.JSON_UTF_8,
                                                requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }

        @Test
        void nonNotificationRequest_DelegatedServiceReturnsHttp503ServiceUnavailable()
                        throws JsonProcessingException {
                final String requestJsonBody = "{\"params\": [\"unavailable_p1\"]}";
                final String expectedErrorDataString =
                                "Internal server error during delegate execution " +
                                                "for method 'unavailableMethod' " +
                                                "(delegate returned 503 Service Unavailable): " +
                                                "{\"reason\": \"Downstream timeout\"}";
                final JsonRpcError jsonRpcError = JsonRpcError.INTERNAL_ERROR
                                .withData(mapper.getNodeFactory().textNode(expectedErrorDataString));
                final JsonRpcResponse expectedRpcResponse = JsonRpcResponse.ofError(jsonRpcError, "req-013");
                final JsonNode expectedRpcResponseJson = mapper.valueToTree(expectedRpcResponse);
                final AggregatedHttpResponse response = client().execute(
                                HttpRequest.of(HttpMethod.POST, "/test-http503", MediaType.JSON_UTF_8,
                                                requestJsonBody))
                                .aggregate().join();
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(MediaType.JSON_UTF_8, response.contentType());
                final JsonNode actualRpcResponseJson = mapper.readTree(response.contentUtf8());
                assertEquals(expectedRpcResponseJson, actualRpcResponseJson);
        }
}
