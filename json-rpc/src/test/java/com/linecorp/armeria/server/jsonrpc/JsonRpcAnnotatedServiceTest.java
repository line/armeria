/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.server.annotation.ProducesJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Test class for JSON-RPC 2.0 examples using AnnotatedService.
 * This test implements examples from https://www.jsonrpc.org/specification#examples
 */
class JsonRpcAnnotatedServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * A test service with JSON-RPC methods.
     */
    private static class JsonRpcTestService {
        /**
         * The subtract method used in examples.
         */
        @Post("/subtract")
        @ProducesJson
        public int subtract(JsonNode params) {
            // Handle both positional and named parameters
            if (params.isArray()) {
                // Positional parameters
                ArrayNode array = (ArrayNode) params;
                return array.get(0).asInt() - array.get(1).asInt();
            } else {
                // Named parameters
                ObjectNode object = (ObjectNode) params;
                return object.get("minuend").asInt() - object.get("subtrahend").asInt();
            }
        }

        /**
         * The update method used in notification example.
         */
        @Post("/update")
        public void update(@RequestObject @Nullable JsonNode params) {
            // This is a notification method, so no return value is needed
        }

    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // Register the JSON-RPC test service
            sb.annotatedService("/jsonrpc", new JsonRpcTestService());
            
            // Apply JSON-RPC decorator to all services
            sb.decorator(JsonRpcServiceDecorator::new);
        }
    };

    private WebClient client() {
        return WebClient.of(server.httpUri());
    }

    /**
     * Helper method to send a JSON-RPC request and parse the response.
     */
    private JsonNode sendJsonRpcRequest(String json) throws Exception {
        AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .post("/jsonrpc")
                        .content(MediaType.JSON, json)
                        .build())
                .aggregate().join();
        
        // JSON-RPC always returns 200 OK for properly formatted requests, even for errors
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.contentUtf8());
    }

    @Test
    void testRpcCallWithPositionalParameters() throws Exception {
        // Test case 1: Subtract 42 - 23 = 19
        String request1 = "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42, 23], \"id\": 1}";
        JsonNode response1 = sendJsonRpcRequest(request1);
        
        assertThat(response1.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response1.get("result").asInt()).isEqualTo(19);
        assertThat(response1.get("id").asInt()).isEqualTo(1);
        
        // Test case 2: Subtract 23 - 42 = -19
        String request2 = "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [23, 42], \"id\": 2}";
        JsonNode response2 = sendJsonRpcRequest(request2);
        
        assertThat(response2.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response2.get("result").asInt()).isEqualTo(-19);
        assertThat(response2.get("id").asInt()).isEqualTo(2);
    }

    @Test
    void testRpcCallWithNamedParameters() throws Exception {
        // Test case 1: Subtract with named parameters in one order
        String request1 = "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", " +
                         "\"params\": {\"subtrahend\": 23, \"minuend\": 42}, \"id\": 3}";
        JsonNode response1 = sendJsonRpcRequest(request1);
        System.out.println(response1);
        
        assertThat(response1.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response1.get("result").asInt()).isEqualTo(19);
        assertThat(response1.get("id").asInt()).isEqualTo(3);
        
        // Test case 2: Subtract with named parameters in another order
        String request2 = "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", " +
                         "\"params\": {\"minuend\": 42, \"subtrahend\": 23}, \"id\": 4}";
        JsonNode response2 = sendJsonRpcRequest(request2);
        
        assertThat(response2.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response2.get("result").asInt()).isEqualTo(19);
        assertThat(response2.get("id").asInt()).isEqualTo(4);
    }

    @Test
    void testNotification() throws Exception {
        // Notification doesn't return any response
        String request = "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [1,2,3,4,5]}";
        AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .post("/jsonrpc")
                        .content(MediaType.JSON, request)
                        .build())
                .aggregate().join();
        
        // notifications, JSON-RPC returns 204 NO_CONTENT
        assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
        // But the content should be empty
        assertThat(response.content().isEmpty()).isTrue();
    }

    @Test
    void testRpcCallOfNonExistentMethod() throws Exception {
        String request = "{\"jsonrpc\": \"2.0\", \"method\": \"nonExistentMethod\", \"id\": \"1\"}";
        JsonNode response = sendJsonRpcRequest(request);
        
        assertThat(response.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(response.get("error").get("message").asText()).isEqualTo("Method not found");
        assertThat(response.get("id").asText()).isEqualTo("1");
    }

    @Test
    void testRpcCallWithInvalidJson() throws Exception {
        String request = "{\"jsonrpc\": \"2.0\", \"method\": \"foobar, \"params\": \"bar\", \"baz]";
        AggregatedHttpResponse response = client().execute(
                HttpRequest.builder()
                        .post("/jsonrpc")
                        .content(MediaType.JSON, request)
                        .build())
                .aggregate().join();
        
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        JsonNode jsonResponse = mapper.readTree(response.contentUtf8());
        
        assertThat(jsonResponse.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(jsonResponse.get("error").get("code").asInt()).isEqualTo(-32700);
        assertThat(jsonResponse.get("error").get("message").asText()).isEqualTo("Parse error");
        assertThat(jsonResponse.has("id")).isTrue();
        assertThat(jsonResponse.get("id").isNull()).isTrue();
    }

    @Test
    void testRpcCallWithInvalidRequestObject() throws Exception {
        String request = "{\"jsonrpc\": \"2.0\", \"method\": 1, \"params\": \"bar\"}";
        JsonNode response = sendJsonRpcRequest(request);
        
        assertThat(response.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32600);
        assertThat(response.get("error").get("message").asText()).isEqualTo("Invalid Request");
        assertThat(response.has("id")).isTrue();
        assertThat(response.get("id").isNull()).isTrue();
    }
} 