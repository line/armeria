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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.JacksonUtil;

class JsonRpcUtilTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    @Test
    void parseDelegateResponse_success() throws JsonProcessingException {
        final String successJson = "{\"result\": \"success\"}";
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.OK, MediaType.JSON, HttpData.ofUtf8(successJson));
        final JsonNode expectedResult = mapper.readTree(successJson).get("result");

        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, 1, "testMethod", mapper);

        assertThat(rpcResponse.error()).isNull();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat(rpcResponse.result()).isEqualTo(mapper.readTree("{\"result\": \"success\"}"));
    }

    @Test
    void parseDelegateResponse_successEmptyContent() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.NO_CONTENT, MediaType.JSON, HttpData.empty());

        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, "req-2", "testMethod", mapper);

        assertThat(rpcResponse.error()).isNull();
        assertThat(rpcResponse.id()).isEqualTo("req-2");
        assertThat(rpcResponse.result()).isNull();
    }

    @Test
    void parseDelegateResponse_successInvalidJsonContent() throws JsonProcessingException {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.ofUtf8("not json"));

        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, 3, "testMethod", mapper);

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo(3);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.message());
    }

    @Test
    void parseDelegateResponse_notFound() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(HttpStatus.NOT_FOUND);
        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, "id-4", "notFoundMethod", mapper);

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo("id-4");
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.METHOD_NOT_FOUND.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.METHOD_NOT_FOUND.message());
    }

    @Test
    void parseDelegateResponse_badRequest() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, "Invalid parameter value");
        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, 5, "badParamMethod", mapper);

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo(5);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS.message());
    }

    @Test
    void parseDelegateResponse_otherClientError() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(HttpStatus.UNAUTHORIZED);
        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, null, "authMethod", mapper);

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isNull();
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.message());
    }

    @Test
    void parseDelegateResponse_serverError() {
        final AggregatedHttpResponse delegateResponse = AggregatedHttpResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "DB connection failed");
        final JsonRpcResponse rpcResponse = JsonRpcUtil.parseDelegateResponse(
                delegateResponse, "req-err", "processData", mapper);

        assertThat(rpcResponse.error()).isNotNull();
        assertThat(rpcResponse.id()).isEqualTo("req-err");
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.code());
        assertThat(rpcResponse.error().message()).isEqualTo(JsonRpcErrorCode.INTERNAL_ERROR.message());
    }

    // --- parseJsonNodeToRequest Tests ---

    @Test
    void parseJsonNodeToRequest_valid() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42, 23], \"id\": 1}";
        final JsonNode node = mapper.readTree(json);
        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(node, mapper);

        assertThat(request.jsonrpc()).isEqualTo("2.0");
        assertThat(request.method()).isEqualTo("subtract");
        assertThat(request.params()).isEqualTo(mapper.readTree("[42, 23]"));
        assertThat(request.id()).isEqualTo(1);
        assertThat(request.isNotification()).isFalse();
    }

    @Test
    void parseJsonNodeToRequest_validNotification() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": {\"key\":\"value\"}}";
        final JsonNode node = mapper.readTree(json);
        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(node, mapper);

        assertThat(request.jsonrpc()).isEqualTo("2.0");
        assertThat(request.method()).isEqualTo("update");
        assertThat(request.params()).isEqualTo(mapper.readTree("{\"key\":\"value\"}"));
        assertThat(request.id()).isNull(); // No ID for notification
        assertThat(request.isNotification()).isTrue();
    }

    @Test
    void parseJsonNodeToRequest_invalidJsonType() {
        final JsonNode node = new TextNode("not an object");
        assertThatThrownBy(() -> JsonRpcUtil.parseJsonNodeToRequest(node, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Request item must be a JSON object");
    }

    @Test
    void parseJsonNodeToRequest_missingVersion() throws JsonProcessingException {
        final String json = "{\"method\": \"foo\", \"id\": 1}";
        final JsonNode node = mapper.readTree(json);
        assertThatThrownBy(() -> JsonRpcUtil.parseJsonNodeToRequest(node, mapper))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void parseJsonNodeToRequest_invalidVersion() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"1.0\", \"method\": \"foo\", \"id\": 1}";
        final JsonNode node = mapper.readTree(json);
        assertThatThrownBy(() -> JsonRpcUtil.parseJsonNodeToRequest(node, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON-RPC version: 1.0");
    }

    @Test
    void parseJsonNodeToRequest_missingMethod() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"id\": 1}";
        final JsonNode node = mapper.readTree(json);
        assertThatThrownBy(() -> JsonRpcUtil.parseJsonNodeToRequest(node, mapper))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("Missing required creator property 'method'");
    }

    @Test
    void parseJsonNodeToRequest_invalidParamsType() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"foo\", \"params\": \"string\", \"id\": 1}";
        final JsonNode node = mapper.readTree(json);
        assertThatThrownBy(() -> JsonRpcUtil.parseJsonNodeToRequest(node, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'params' must be an object or an array");
    }

    @Test
    void parseJsonNodeToRequest_validNullParams() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"ping\", \"params\": null, \"id\": \"abc\"}";
        final JsonNode node = mapper.readTree(json);
        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(node, mapper);

        assertThat(request.method()).isEqualTo("ping");
        assertThat(request.params()).isEqualTo(NullNode.instance);
        assertThat(request.id()).isEqualTo("abc");
    }

    @Test
    void parseJsonNodeToRequest_validMissingParams() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"ping\", \"id\": 123}";
        final JsonNode node = mapper.readTree(json);
        final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(node, mapper);

        assertThat(request.method()).isEqualTo("ping");
        assertThat(request.params()).isNull();
        assertThat(request.id()).isEqualTo(123);
    }
}
