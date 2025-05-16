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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcUtil;

class JsonRpcRequestParserTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private AggregatedHttpRequest createHttpRequest(String content) {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(
                        HttpMethod.POST,
                        "/",
                        HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8.toString()),
                HttpData.ofUtf8(content));
    }

    @Test
    void parseRequest_singleValidRequestWithId() throws Exception {
        // Test case 1: Normal single JSON-RPC request (with ID)
        final String requestJson =
                "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42, 23], \"id\": 1}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertFalse(result.isError());
        assertNotNull(result.request());
        assertNull(result.errorResponse());

        final JsonRpcRequest jsonRpcRequest = result.request();
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, jsonRpcRequest.jsonRpcVersion());
        assertEquals("subtract", jsonRpcRequest.method());
        assertEquals(mapper.readTree("[42, 23]"), jsonRpcRequest.params());
        assertEquals(1, jsonRpcRequest.id());
    }

    @Test
    void parseRequest_singleValidNotificationRequest() throws Exception {
        // Test case 2: Normal single JSON-RPC notification request (no ID)
        final String requestJson = "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [1,2,3,4,5]}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertFalse(result.isError());
        assertNotNull(result.request());

        final JsonRpcRequest jsonRpcRequest = result.request();
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, jsonRpcRequest.jsonRpcVersion());
        assertEquals("update", jsonRpcRequest.method());
        assertEquals(mapper.readTree("[1,2,3,4,5]"), jsonRpcRequest.params());
        assertNull(jsonRpcRequest.id());
    }

    @Test
    void parseRequest_singleValidRequestWithNullId() throws Exception {
        // Test case 3: Normal single JSON-RPC request (ID is null)
        final String requestJson =
                "{\"jsonrpc\": \"2.0\", \"method\": \"foo\", \"params\": {\"bar\": \"baz\"}, \"id\": null}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertFalse(result.isError());
        assertNotNull(result.request());

        final JsonRpcRequest jsonRpcRequest = result.request();
        assertEquals(JsonRpcUtil.JSON_RPC_VERSION, jsonRpcRequest.jsonRpcVersion());
        assertEquals("foo", jsonRpcRequest.method());
        assertEquals(mapper.readTree("{\"bar\": \"baz\"}"), jsonRpcRequest.params());
        assertNull(jsonRpcRequest.id());
    }

    @Test
    void parseRequest_validBatchRequest() throws Exception {
        // Test case 4: Normal batch JSON-RPC request (multiple valid requests)
        final String requestJson =
                "[{\"jsonrpc\": \"2.0\", \"method\": \"sum\", \"params\": [1,2,4], \"id\": \"1\"}, " +
                        "{\"jsonrpc\": \"2.0\", \"method\": \"notify_hello\", \"params\": [7]}, " +
                        "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42,23], \"id\": \"2\"}]";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(3);

        // First request
        final JsonRpcItemParseResult result1 = results.get(0);
        assertFalse(result1.isError());
        final JsonRpcRequest req1 = result1.request();
        assertNotNull(req1);
        assertEquals("sum", req1.method());
        assertEquals("1", req1.id());

        // Second request (notification)
        final JsonRpcItemParseResult result2 = results.get(1);
        assertFalse(result2.isError());
        final JsonRpcRequest req2 = result2.request();
        assertNotNull(req2);
        assertEquals("notify_hello", req2.method());
        assertNull(req2.id());

        // Third request
        final JsonRpcItemParseResult result3 = results.get(2);
        assertFalse(result3.isError());
        final JsonRpcRequest req3 = result3.request();
        assertNotNull(req3);
        assertEquals("subtract", req3.method());
        assertEquals("2", req3.id());
    }

    @Test
    void parseRequest_invalidJsonFormatTopLevel() {
        // Test case 5: Invalid JSON format (top level)
        final String requestJson = "{\"jsonrpc\": \"2.0\", \"method\": \"foo\", \"params\": [1,2";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNull(result.request());
        assertNotNull(result.errorResponse());

        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.PARSE_ERROR.code(), errorResponse.error().code());
        assertNull(errorResponse.id());
    }

    @Test
    void parseRequest_notJsonRpcObjectMissingJsonRpcField() throws Exception {
        // Test case 6: JSON object that is not a JSON-RPC request (e.g., "jsonrpc" field missing)
        final String requestJson = "{\"method\": \"foo\", \"id\": 1}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());

        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertEquals(1, errorResponse.id());
    }

    @Test
    void parseRequest_notJsonObjectOrArray() {
        // Test case 7: Non-JSON object or array type (number, string, etc.)
        final String requestJson = "123";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());

        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertNull(errorResponse.id());
    }

    @Test
    void parseRequest_emptyBatchRequest() {
        // Test case 8: Empty batch JSON-RPC request (`[]`)
        final String requestJson = "[]";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());

        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertNull(errorResponse.id());
    }

    @Test
    void parseRequest_batchWithValidAndInvalidItems() throws Exception {
        // Test case 9: Batch request with some valid and some invalid items
        final String requestJson =
                "[{\"jsonrpc\": \"2.0\", \"method\": \"sum\", \"params\": [1,2,4], \"id\": \"1\"}, " +
                        "{\"method\": \"invalid\"}]";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(2);
        // First item (valid)
        final JsonRpcItemParseResult result1 = results.get(0);
        assertFalse(result1.isError());
        assertNotNull(result1.request());
        assertEquals("1", result1.request().id());
        // Second item (invalid)
        final JsonRpcItemParseResult result2 = results.get(1);
        assertTrue(result2.isError());
        assertNotNull(result2.errorResponse());
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), result2.errorResponse().error().code());
        assertNull(result2.errorResponse().id());
    }

    @Test
    void parseRequest_batchWithNonJsonObjectItem() throws Exception {
        // Test case 10: Batch request with a non-JSON object item
        final String requestJson = "[{\"jsonrpc\": \"2.0\", \"method\": \"sum\", \"id\": \"1\"}, 123]";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(2);
        // First item (valid)
        final JsonRpcItemParseResult result1 = results.get(0);
        assertFalse(result1.isError());
        assertNotNull(result1.request());
        assertEquals("1", result1.request().id());
        // Second item (invalid - not an object)
        final JsonRpcItemParseResult result2 = results.get(1);
        assertTrue(result2.isError());
        assertNotNull(result2.errorResponse());
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), result2.errorResponse().error().code());
        assertNull(result2.errorResponse().id());
    }

    @Test
    void parseRequest_validParamsTypes() throws Exception {
        // Test case 11: "params" field with various valid types (array, object)
        // Request 1: params as object
        final String requestJson1 =
                "{" +
                        "\"jsonrpc\": \"2.0\", " +
                        "\"method\": \"byName\", " +
                        "\"params\": {\"name\": \"armeria\"}, " +
                        "\"id\": 10" +
                        "}";
        final AggregatedHttpRequest httpRequest1 = createHttpRequest(requestJson1);
        final List<JsonRpcItemParseResult> results1 = JsonRpcRequestParser.parseRequest(httpRequest1);

        assertThat(results1).hasSize(1);
        final JsonRpcRequest req1 = results1.get(0).request();
        assertNotNull(req1);
        assertTrue(req1.params().isObject());
        assertEquals(mapper.readTree("{\"name\": \"armeria\"}"), req1.params());
        // Request 2: params as array
        final String requestJson2 =
                "{\"jsonrpc\": \"2.0\", \"method\": \"byPosition\", \"params\": [1, 2], \"id\": 11}";
        final AggregatedHttpRequest httpRequest2 = createHttpRequest(requestJson2);
        final List<JsonRpcItemParseResult> results2 = JsonRpcRequestParser.parseRequest(httpRequest2);

        assertThat(results2).hasSize(1);
        final JsonRpcRequest req2 = results2.get(0).request();
        assertNotNull(req2);
        assertTrue(req2.params().isArray());
        assertEquals(mapper.readTree("[1, 2]"), req2.params());
    }

    @Test
    void parseRequest_invalidParamsType() throws Exception {
        // Test case 12: "params" field with invalid type (e.g., string)
        final String requestJson =
                "{\"jsonrpc\": \"2.0\", \"method\": \"test\", \"params\": \"invalid\", \"id\": 1}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());
        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertEquals(1, errorResponse.id());
    }

    @Test
    void parseRequest_validIdTypes() throws Exception {
        // Test case 13: "id" field with various valid types (string, number)
        // Request 1: ID as string
        final String requestJson1 =
                "{\"jsonrpc\": \"2.0\", \"method\": \"echo\", \"params\": [\"hello\"], \"id\": \"abc\"}";
        final AggregatedHttpRequest httpRequest1 = createHttpRequest(requestJson1);
        final List<JsonRpcItemParseResult> results1 = JsonRpcRequestParser.parseRequest(httpRequest1);
        assertThat(results1).hasSize(1);
        final JsonRpcRequest req1 = results1.get(0).request();
        assertNotNull(req1);
        assertEquals("abc", req1.id());
        // Request 2: ID as integer
        final String requestJson2 =
                "{\"jsonrpc\": \"2.0\", \"method\": \"echo\", \"params\": [\"world\"], \"id\": 123}";
        final AggregatedHttpRequest httpRequest2 = createHttpRequest(requestJson2);
        final List<JsonRpcItemParseResult> results2 = JsonRpcRequestParser.parseRequest(httpRequest2);
        assertThat(results2).hasSize(1);
        final JsonRpcRequest req2 = results2.get(0).request();
        assertNotNull(req2);
        assertEquals(123, req2.id());
        // Request 3: ID as floating point number
        final String requestJson3 =
                "{\"jsonrpc\": \"2.0\", \"method\": \"echo\", \"params\": [\"test\"], \"id\": 1.5}";
        final AggregatedHttpRequest httpRequest3 = createHttpRequest(requestJson3);
        final List<JsonRpcItemParseResult> results3 = JsonRpcRequestParser.parseRequest(httpRequest3);
        assertThat(results3).hasSize(1);
        final JsonRpcRequest req3 = results3.get(0).request();
        assertNotNull(req3);
        assertEquals(1.5, req3.id());
    }

    @Test
    void parseRequest_invalidIdType_object() throws Exception {
        // Test case 14: "id" field with invalid type (object)
        final String requestJson =
                "{" +
                        "\"jsonrpc\": \"2.0\", " +
                        "\"method\": \"testMethod\", " +
                        "\"params\":[], " +
                        "\"id\": {\"invalid\": true}" +
                        "}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());
        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertNull(errorResponse.id());
    }

    @Test
    void parseRequest_invalidIdType_array() throws Exception {
        // Test case 14: "id" field with invalid type (array)
        final String requestJson =
                "{\"jsonrpc\": \"2.0\", \"method\": \"testMethod\", \"params\":[], \"id\": [\"invalid\"]}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());
        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertNull(errorResponse.id());
    }

    @Test
    void parseRequest_invalidIdType_boolean() throws Exception {
        // Test case 14: "id" field with invalid type (boolean)
        final String requestJson =
                "{\"jsonrpc\": \"2.0\", \"method\": \"testMethod\", \"params\":[], \"id\": true}";
        final AggregatedHttpRequest httpRequest = createHttpRequest(requestJson);

        final List<JsonRpcItemParseResult> results = JsonRpcRequestParser.parseRequest(httpRequest);

        assertThat(results).hasSize(1);
        final JsonRpcItemParseResult result = results.get(0);
        assertTrue(result.isError());
        assertNotNull(result.errorResponse());
        final JsonRpcResponse errorResponse = result.errorResponse();
        assertEquals(JsonRpcError.INVALID_REQUEST.code(), errorResponse.error().code());
        assertNull(errorResponse.id());
    }
}
