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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcErrorCode;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class JsonRpcServiceBuilderTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final JsonNodeFactory factory = mapper.getNodeFactory();
    private static final Logger logger = LoggerFactory.getLogger(JsonRpcServiceBuilderTest.class);

    private static class RequestDTO {

        @JsonCreator
        RequestDTO() {
        }

        RequestDTO(int minuend, int subtrahend) {
            this.minuend = minuend;
            this.subtrahend = subtrahend;
        }

        private int minuend;

        private int subtrahend;

        public int getMinuend() {
            return minuend;
        }

        public int getSubtrahend() {
            return subtrahend;
        }
    }

    private static class JsonRpcTestService {

        @Post("/returnResult")
        @ProducesJson
        public JsonNode returnResult() {
            final Map<String, String> result = new HashMap<>();
            result.put("message", "success");
            return mapper.valueToTree(result);
        }

        @Post("/subtractByPos")
        public HttpResponse subtractByPos(@RequestObject Object[] arr) {
             return HttpResponse.of(Integer.toString((Integer) arr[0] - (Integer) arr[1]));
        }

        @Post("/subtractByName")
        public HttpResponse subtractByName(@RequestObject RequestDTO requestDTO) {
            return HttpResponse.of(Integer.toString(requestDTO.getMinuend() - requestDTO.getSubtrahend()));
        }

        @Post("/update")
        public void update(@RequestObject JsonNode data) {
            System.out.println("Received update notification: " + data);
        }

        @Post("/getData")
        @ProducesJson
        public Object getData() {
            return mapper.valueToTree(Arrays.asList("hello", 5));
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder("/json-rpc", new JsonRpcServiceBuilder(sb)
                    .addAnnotatedService("/test", new JsonRpcTestService())
                    .build()
            );
            sb.requestTimeoutMillis(0);
            sb.verboseResponses(true);
        }
    };

    private WebClient client() {
        return WebClient.builder(server.httpUri())
                        .responseTimeoutMillis(0)
                        .build();
    }

    private String createJsonRpcRequest(String method, @Nullable Object params, @Nullable Object id) {
        final ObjectNode requestJson = factory.objectNode();
        requestJson.put("jsonrpc", "2.0");
        requestJson.put("method", method);
        if (params != null) {
            requestJson.set("params", mapper.valueToTree(params));
        }
        if (id == null) {
            requestJson.set("id", factory.nullNode());
        } else if (id instanceof String) {
            requestJson.put("id", (String) id);
        } else if (id instanceof Number) {
            final Number numId = (Number) id;
            if (id instanceof Integer) {
                requestJson.put("id", numId.intValue());
            } else if (id instanceof Long) {
                requestJson.put("id", numId.longValue());
            } else if (id instanceof Double) {
                requestJson.put("id", numId.doubleValue());
            } else if (id instanceof Float) {
                requestJson.put("id", numId.floatValue());
            } else if (id instanceof BigDecimal) {
                requestJson.set("id", factory.numberNode((BigDecimal) id));
            } else if (id instanceof BigInteger) {
                requestJson.set("id", factory.numberNode((BigInteger) id));
            } else {
                requestJson.put("id", numId.doubleValue());
            }
        } else {
            throw new IllegalArgumentException("Unsupported ID type: " + id.getClass().getName());
        }
        try {
            return mapper.writeValueAsString(requestJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertJsonRpcSuccess(AggregatedHttpResponse response,
                                      Object expectedResult,
                                      Object expectedId) throws JsonProcessingException {
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        final JsonNode responseJson = mapper.readTree(response.contentUtf8());
        assertThat(responseJson.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(responseJson.has("error")).isFalse();
        assertThat(responseJson.has("result")).isTrue();
        assertThat(responseJson.get("result")).isEqualTo(mapper.valueToTree(expectedResult));

         final JsonNode idNode = responseJson.get("id");
         if (expectedId == null) {
             assertThat(idNode.isNull()).isTrue();
         } else if (expectedId instanceof String) {
             assertThat(idNode.isTextual()).isTrue();
             assertThat(idNode.asText()).isEqualTo((String) expectedId);
         } else if (expectedId instanceof Number) {
             assertThat(idNode.isNumber()).isTrue();
             final Number expectedNum = (Number) expectedId;
             if (expectedId instanceof Integer) {
                 assertThat(idNode.asInt()).isEqualTo(expectedNum.intValue());
             } else if (expectedId instanceof Long) {
                 assertThat(idNode.asLong()).isEqualTo(expectedNum.longValue());
             } else if (expectedId instanceof Double) {
                 assertThat(idNode.asDouble()).isEqualTo(expectedNum.doubleValue());
             } else if (expectedId instanceof Float) {
                 assertThat(idNode.floatValue()).isEqualTo(expectedNum.floatValue());
             } else if (expectedId instanceof BigDecimal) {
                 assertThat(idNode.decimalValue()).isEqualTo((BigDecimal)expectedId);
             } else if (expectedId instanceof BigInteger) {
                 assertThat(idNode.bigIntegerValue()).isEqualTo((BigInteger)expectedId);
             } else {
                 assertThat(idNode.asDouble()).isEqualTo(expectedNum.doubleValue());
             }
         } else {
             assertThat(idNode).isEqualTo(mapper.valueToTree(expectedId));
         }
    }

    private void assertJsonRpcError(AggregatedHttpResponse response,
                                    JsonRpcErrorCode errorCode,
                                    @Nullable Object expectedId) throws JsonProcessingException {
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        final JsonNode responseJson = mapper.readTree(response.contentUtf8());
        assertThat(responseJson.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(responseJson.has("result")).isFalse();
        assertThat(responseJson.has("error")).isTrue();
        final JsonNode errorJson = responseJson.get("error");
        assertThat(errorJson.get("code").asInt()).isEqualTo(errorCode.code());
        assertThat(errorJson.get("message").asText()).contains(errorCode.message());

        final JsonNode idNode = responseJson.get("id");
        if (expectedId == null) {
             assertThat(idNode.isNull()).isTrue();
        } else if (expectedId instanceof String) {
            assertThat(idNode.isTextual()).isTrue();
            assertThat(idNode.asText()).isEqualTo((String) expectedId);
        } else if (expectedId instanceof Number) {
            assertThat(idNode.isNumber()).isTrue();
            final Number expectedNum = (Number) expectedId;
            if (expectedId instanceof Integer) {
                assertThat(idNode.asInt()).isEqualTo(expectedNum.intValue());
            } else if (expectedId instanceof Long) {
                assertThat(idNode.asLong()).isEqualTo(expectedNum.longValue());
            } else if (expectedId instanceof Double) {
                assertThat(idNode.asDouble()).isEqualTo(expectedNum.doubleValue());
            } else if (expectedId instanceof Float) {
                assertThat(idNode.floatValue()).isEqualTo(expectedNum.floatValue());
            } else if (expectedId instanceof BigDecimal) {
                assertThat(idNode.decimalValue()).isEqualTo((BigDecimal)expectedId);
            } else if (expectedId instanceof BigInteger) {
                assertThat(idNode.bigIntegerValue()).isEqualTo((BigInteger)expectedId);
            } else {
                assertThat(idNode.asDouble()).isEqualTo(expectedNum.doubleValue());
            }
        } else {
            assertThat(idNode).isEqualTo(mapper.valueToTree(expectedId));
        }
    }

    @Test
    void success_noParams() throws Exception {
        final String requestBody = createJsonRpcRequest("returnResult", null, 1);
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();

        final Map<String, String> result = new HashMap<>();
        result.put("message", "success");

        assertJsonRpcSuccess(response, result, 1);
    }

     @Test
    void success_positionalParams() throws Exception {
        final String requestBody = createJsonRpcRequest("subtractByPos", new int[]{42, 23}, 2);
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();

        assertJsonRpcSuccess(response, 19, 2);
    }

     @Test
    void success_namedParams() throws Exception {
        final String requestBody = createJsonRpcRequest("subtractByName",
                                                        new RequestDTO(43, 24),
                                                        "req-3");
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();

        assertJsonRpcSuccess(response, 19, "req-3");
    }

    @Test
    void success_arrayResult() throws Exception {
        final String requestBody = createJsonRpcRequest("getData", null, 9);
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();

        assertJsonRpcSuccess(response, mapper.valueToTree(Arrays.asList("hello", 5)), 9);
    }

    @Test
    void notification_noParams() throws Exception {
        final String requestBody = createJsonRpcRequest("update",
                                                        Collections.singletonMap("data", "some data"),
                                                        null);
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();

         assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void error_methodNotFound() throws Exception {
        final String requestBody = createJsonRpcRequest("nonExistentMethod",
                                                        null,
                                                        "err-1");
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();
         assertJsonRpcError(response, JsonRpcErrorCode.METHOD_NOT_FOUND, "err-1");

        final AggregatedHttpResponse response2 = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/nonExistentPath")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();
        assertJsonRpcError(response2, JsonRpcErrorCode.METHOD_NOT_FOUND, "err-1");
    }

     @Test
    void error_parseError_invalidJson() throws Exception {
         final String invalidJson =
                 "{\"jsonrpc\": \"2.0\", \"method\": \"foo\", \"params\": \"bar\", \"id\": 1";
         final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, invalidJson)
                                .build())
                .aggregate().join();
         assertJsonRpcError(response, JsonRpcErrorCode.PARSE_ERROR, null);
    }

    @Test
    void error_invalidRequest_missingMethod() throws Exception {
        final String requestBody = "{\"jsonrpc\": \"2.0\", \"params\": [1, 2], \"id\": 1}";
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();
        assertJsonRpcError(response, JsonRpcErrorCode.INVALID_REQUEST, 1);
    }

     @Test
    void error_invalidRequest_wrongVersion() throws Exception {
        final String requestBody = "{\"jsonrpc\": \"1.0\", \"method\": \"foo\", \"id\": 1}";
        final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();
         assertJsonRpcError(response, JsonRpcErrorCode.INVALID_REQUEST, 1);
    }

     @Test
    void error_invalidParams_wrongType() throws Exception {
        final String requestBody =
                createJsonRpcRequest("subtractByPos", "not an array", 5);
         final AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/json-rpc/test")
                                .content(MediaType.JSON_UTF_8, requestBody)
                                .build())
                .aggregate().join();
         assertJsonRpcError(response, JsonRpcErrorCode.INVALID_REQUEST, 5);
     }

     @Test
     void error_invalidParams_structureMismatch_posToNamed() throws Exception {
         final Map<String, Integer> params = new HashMap<>();
         params.put("p1", 42);
         params.put("p2", 23);
         final String requestBody = createJsonRpcRequest("subtractByPos", params, 6);
         final AggregatedHttpResponse response = client().execute(
                 HttpRequest.builder()
                         .post("/json-rpc/test")
                         .content(MediaType.JSON_UTF_8, requestBody)
                         .build())
                 .aggregate().join();
         assertJsonRpcError(response, JsonRpcErrorCode.INVALID_PARAMS, 6);
     }

     @Test
     void error_invalidParams_structureMismatch_namedToPos() throws Exception {
         final String requestBody = createJsonRpcRequest("subtractByName",
                                                         new int[]{42, 23},
                                                         7);
         final AggregatedHttpResponse response = client().execute(
                 HttpRequest.builder()
                         .post("/json-rpc/test")
                         .content(MediaType.JSON_UTF_8, requestBody)
                         .build())
                 .aggregate().join();
         assertJsonRpcError(response, JsonRpcErrorCode.INVALID_PARAMS, 7);
     }

     @Test
     void batchRequests() throws Exception {
         // Based on https://www.jsonrpc.org/specification#examples - rpc call Batch
         // String batchRequestBody = """ Replaced with concatenation below
         //         [
         //             {"jsonrpc": "2.0", "method": "sum", "params": [1,2,4], "id": "1"},
         //             {"jsonrpc": "2.0", "method": "notify_hello", "params": [7]},
         //             {"jsonrpc": "2.0", "method": "subtractByPos", "params": [42,23], "id": "2"},
         //             {"foo": "boo"},
         //             {"jsonrpc": "2.0", "method": "nonExistentMethod",
         //                                "params": {"name": "myself"}, "id": "5"},
         //             {"jsonrpc": "2.0", "method": "getData", "id": "9"}
         //         ]
         //         """;

         // Request 1: Use getData with id "1"
         // Request 2: Notification with update
         // Request 3: Use subtractByPos with id "2"
         // Request 4: Invalid request {"foo": "boo"} -> error, id null
         // Request 5: Use nonExistentMethod with id "5" -> error
         // Request 6: Use subtractByName with id "9"

         final String batchRequestBody =
                 "[" +
                 "    {\"jsonrpc\": \"2.0\", \"method\": \"getData\", \"id\": 1}," +
                 "    {\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [7]}," +
                 "    {\"jsonrpc\": \"2.0\", \"method\": \"subtractByPos\", \"params\": [42,23], \"id\": 2}," +
                 "    {\"foo\": \"boo\"}," +
                 "    {\"jsonrpc\": \"2.0\", \"method\": \"nonExistentMethod\", " +
                 "                           \"params\": {\"name\": \"myself\"}, \"id\": \"5\"}," +
                 "    {\"jsonrpc\": \"2.0\", \"method\": \"subtractByName\", " +
                 "                           \"params\": {\"minuend\": 100, \"subtrahend\": 10}, \"id\": 9}" +
                 "]";

         final AggregatedHttpResponse response = client().execute(
                         HttpRequest.builder()
                                 .post("/json-rpc/test")
                                 .content(MediaType.JSON_UTF_8, batchRequestBody)
                                 .build())
                 .aggregate().join();

         logger.info("Batch Response: {}", response.contentUtf8());
         assertThat(response.status()).isEqualTo(HttpStatus.OK);
         assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

         final JsonNode responseArray = mapper.readTree(response.contentUtf8());
         assertThat(responseArray.isArray()).isTrue();
         assertThat(responseArray.size()).isEqualTo(5); // 1 notification excluded

         final Map<Object, JsonNode> responseMap = new HashMap<>();
         for (JsonNode responseNode : responseArray) {
             final JsonNode idNode = responseNode.get("id");
             Object id = null;
             if (idNode != null) {
                 if (idNode.isTextual()) {
                     id = idNode.asText();
                 } else if (idNode.isNumber()) {
                     id = idNode.numberValue(); // Use numberValue for simplicity
                 } else if (idNode.isNull()) {
                     // Handle null id for the invalid request explicitly
                     responseMap.put("invalid_request_null_id", responseNode);
                     continue; // Skip adding to map with null key
                 }
             }
             if (id != null) {
                 responseMap.put(id, responseNode);
             }
         }

         // Verify getData response (id: 1)
         assertThat(responseMap.containsKey(1)).isTrue();
         final JsonNode getDataResponse = responseMap.get(1);
         assertThat(getDataResponse.get("jsonrpc").asText()).isEqualTo("2.0");
         assertThat(getDataResponse.has("error")).isFalse();
         assertThat(getDataResponse.get("result"))
                 .isEqualTo(mapper.valueToTree(Arrays.asList("hello", 5)));
         assertThat(getDataResponse.get("id").asInt()).isEqualTo(1);

         // Verify subtractByPos response (id: 2)
         assertThat(responseMap.containsKey(2)).isTrue();
         final JsonNode subtractPosResponse = responseMap.get(2);
         assertThat(subtractPosResponse.get("jsonrpc").asText()).isEqualTo("2.0");
         assertThat(subtractPosResponse.has("error")).isFalse();
         assertThat(subtractPosResponse.get("result").asInt()).isEqualTo(19); // 42 - 23
         assertThat(subtractPosResponse.get("id").asInt()).isEqualTo(2);

         // Verify invalid request response (id: null)
         assertThat(responseMap.containsKey("invalid_request_null_id")).isTrue();
         final JsonNode invalidReqResponse = responseMap.get("invalid_request_null_id");
         assertThat(invalidReqResponse.get("jsonrpc").asText()).isEqualTo("2.0");
         assertThat(invalidReqResponse.has("result")).isFalse();
         assertThat(invalidReqResponse.has("error")).isTrue();
         assertThat(invalidReqResponse.get("error").get("code").asInt())
                 .isEqualTo(JsonRpcErrorCode.INVALID_REQUEST.code());
         assertThat(invalidReqResponse.get("id").isNull()).isTrue();

         // Verify method not found response (id: "5")
         assertThat(responseMap.containsKey("5")).isTrue();
         final JsonNode notFoundResponse = responseMap.get("5");
         assertThat(notFoundResponse.get("jsonrpc").asText()).isEqualTo("2.0");
         assertThat(notFoundResponse.has("result")).isFalse();
         assertThat(notFoundResponse.has("error")).isTrue();
         assertThat(notFoundResponse.get("error").get("code").asInt())
                 .isEqualTo(JsonRpcErrorCode.METHOD_NOT_FOUND.code());
         assertThat(notFoundResponse.get("id").asText()).isEqualTo("5");

         // Verify subtractByName response (id: 9)
         assertThat(responseMap.containsKey(9)).isTrue();
         final JsonNode subtractNameResponse = responseMap.get(9);
         assertThat(subtractNameResponse.get("jsonrpc").asText()).isEqualTo("2.0");
         assertThat(subtractNameResponse.has("error")).isFalse();
         assertThat(subtractNameResponse.get("result").asInt()).isEqualTo(90); // 100 - 10
         assertThat(subtractNameResponse.get("id").asInt()).isEqualTo(9);

         // Ensure no other responses are present
         assertThat(responseMap.size()).isEqualTo(5); // 1 notification excluded
     }
}
