/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 *  the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, OUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.jsonrpc;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A test for {@link JsonRpcRequest}.
 */
class JsonRpcRequestTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testEquals() {
        final JsonRpcRequest req0 = JsonRpcRequest.of("1", "store", ImmutableMap.of("key", "value"));
        final JsonRpcRequest req1 = JsonRpcRequest.of("1", "store", ImmutableMap.of("key", "value"));
        assertThat(req0).isEqualTo(req1) ;

        final JsonRpcRequest req2 = JsonRpcRequest.of("2", "put", ImmutableList.of("a", "b"));
        final JsonRpcRequest req3 = JsonRpcRequest.of("2", "put", ImmutableList.of("a", "b"));
        assertThat(req2).isEqualTo(req3);
    }

    @Test
    void noParams() {
        final JsonRpcRequest req = JsonRpcRequest.of("1", "subtract");

        assertThat(req.id()).isEqualTo("1");
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params().isPositional()).isTrue();
        assertThat(req.params().asList()).isEmpty();
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void singleParam() {
        final ImmutableList<String> params = ImmutableList.of("param");
        final JsonRpcRequest req = JsonRpcRequest.of(2, "subtract", params);

        assertThat(req.id()).isEqualTo(2);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params().isPositional()).isTrue();
        assertThat(req.params().asList()).containsExactly("param");
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void iterableParams() {
        final List<Object> params = Arrays.asList("foo", "bar", 5);
        final JsonRpcRequest req = JsonRpcRequest.of("abc-123", "update", params);

        assertThat(req.id()).isEqualTo("abc-123");
        assertThat(req.method()).isEqualTo("update");
        assertThat(req.params().isPositional()).isTrue();
        assertThat(req.params().asList()).isEqualTo(params);
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void emptyIterableParams() {
        final JsonRpcRequest req = JsonRpcRequest.of(4, "get_data", ImmutableList.of());

        assertThat(req.id()).isEqualTo(4);
        assertThat(req.method()).isEqualTo("get_data");
        assertThat(req.params().isPositional()).isTrue();
        assertThat(req.params().asList()).isEmpty();
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void namedParam() {
        final Map<String, Integer> params = ImmutableMap.of("subtrahend", 23);
        final JsonRpcRequest req = JsonRpcRequest.of(1, "subtract", params);

        assertThat(req.id()).isEqualTo(1);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params().isNamed()).isTrue();
        assertThat(req.params().asMap()).isEqualTo(params);
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void fromJsonNode() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42, 23], \"id\": 1}";

        final JsonRpcRequest req = JsonRpcRequest.fromJson(json);

        assertThat(req.id()).isEqualTo(1);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params().isPositional()).isTrue();
        assertThat(req.params().asList()).containsExactly(42, 23);
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void fromJsonNode_nullParams() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": null, \"id\": 1}";
        final JsonNode node = mapper.readTree(json);

        final JsonRpcRequest req = JsonRpcRequest.fromJson(node);

        assertThat(req.id()).isEqualTo(1);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params().isPositional()).isTrue();
        assertThat(req.params().asList()).isEmpty();
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void fromJsonNode_namedParams() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": {\"subtrahend\": 23}, \"id\": 3}";
        final JsonNode node = mapper.readTree(json);
        final JsonRpcRequest req = JsonRpcRequest.fromJson(node);

        assertThat(req.id()).isEqualTo(3);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params().isNamed()).isTrue();
        assertThat(req.params().asMap()).containsEntry("subtrahend", 23);
        assertThat(req.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void fromJsonNode_notAnObject() throws JsonProcessingException {
        final JsonNode node = mapper.readTree("[]");

        assertThatThrownBy(() -> JsonRpcRequest.fromJson(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot deserialize value of type");
    }

    @Test
    void fromJsonNode_missingRequiredField() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"id\": 1}";
        final JsonNode node = mapper.readTree(json);

        assertThatThrownBy(() -> JsonRpcRequest.fromJson(node))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serializePositional() throws JsonProcessingException {
        final JsonRpcRequest req = JsonRpcRequest.of(1, "subtract", ImmutableList.of(42, 23));
        final String json = mapper.writeValueAsString(req);

        assertThatJson(json).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"method\":\"subtract\",\"params\":[42,23],\"id\":1}");
    }

    @Test
    void serializeNamed() throws JsonProcessingException {
        final JsonRpcRequest req = JsonRpcRequest.of(1, "subtract", ImmutableMap.of("foo", "bar"));
        final String json = mapper.writeValueAsString(req);
        assertThatJson(json).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"method\":\"subtract\",\"params\":{\"foo\":\"bar\"},\"id\":1}");
    }
}
