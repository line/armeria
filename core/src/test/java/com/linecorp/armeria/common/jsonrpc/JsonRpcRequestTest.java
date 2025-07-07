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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A test for {@link JsonRpcRequest}.
 */
class JsonRpcRequestTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void of_withNoParams() {
        final JsonRpcRequest req = JsonRpcRequest.of("1", "subtract");

        assertThat(req.id()).isEqualTo("1");
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params()).isEmpty();
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_withSingleParam() {
        final List<Integer> singleParam = Arrays.asList(42, 23);
        final JsonRpcRequest req = JsonRpcRequest.of(2, "subtract", singleParam);

        assertThat(req.id()).isEqualTo(2);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params()).containsExactly(42, 23);
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_withSingleNullParam() {
        final JsonRpcRequest req = JsonRpcRequest.of(3, "subtract", (Object) null);

        assertThat(req.id()).isEqualTo(3);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params()).isEmpty();
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_withIterableParams() {
        final List<Object> params = Arrays.asList("foo", "bar", 5);
        final JsonRpcRequest req = JsonRpcRequest.of("abc-123", "update", params);

        assertThat(req.id()).isEqualTo("abc-123");
        assertThat(req.method()).isEqualTo("update");
        assertThat(req.params()).isEqualTo(params);
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_withEmptyIterableParams() {
        final JsonRpcRequest req = JsonRpcRequest.of(4, "get_data", Collections.emptyList());

        assertThat(req.id()).isEqualTo(4);
        assertThat(req.method()).isEqualTo("get_data");
        assertThat(req.params()).isEmpty();
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_withVarargsParams() {
        final JsonRpcRequest req = JsonRpcRequest.of(null, "sum", 1, 2, 4);

        assertThat(req.id()).isNull();
        assertThat(req.method()).isEqualTo("sum");
        assertThat(req.params()).containsExactly(1, 2, 4);
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_fromJsonNode() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42, 23], \"id\": 1}";
        final JsonNode node = mapper.readTree(json);

        final JsonRpcRequest req = JsonRpcRequest.of(node);

        assertThat(req.id()).isEqualTo(1);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params()).containsExactly(42, 23);
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_fromJsonNode_withNullParams() throws JsonProcessingException {
        final String json =
                "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": null, \"id\": 1}";
        final JsonNode node = mapper.readTree(json);

        final JsonRpcRequest req = JsonRpcRequest.of(node);

        assertThat(req.id()).isEqualTo(1);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params()).isEmpty();
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_fromJsonNode_withNamedParams() throws JsonProcessingException {
        final String json =
            "{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": {\"subtrahend\": 23}, \"id\": 3}";
        final JsonNode node = mapper.readTree(json);
        final JsonNode paramsNode = node.get("params");

        final JsonRpcRequest req = JsonRpcRequest.of(node);

        assertThat(req.id()).isEqualTo(3);
        assertThat(req.method()).isEqualTo("subtract");
        assertThat(req.params()).hasSize(1);
        assertThat(req.params().get(0)).isEqualTo(mapper.treeToValue(paramsNode, Object.class));
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_fromJsonNode_notification() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [1,2,3,4,5]}";
        final JsonNode node = mapper.readTree(json);

        final JsonRpcRequest req = JsonRpcRequest.of(node);

        assertThat(req.id()).isNull();
        assertThat(req.method()).isEqualTo("update");
        assertThat(req.params()).containsExactly(1, 2, 3, 4, 5);
        assertThat(req.version()).isEqualTo("2.0");
    }

    @Test
    void of_fromJsonNode_notAnObject() throws JsonProcessingException {
        final JsonNode node = mapper.readTree("[]");

        assertThatThrownBy(() -> JsonRpcRequest.of(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("node.isObject(): false (expected: true)");
    }

    @Test
    void of_fromJsonNode_missingRequiredField() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"id\": 1}";
        final JsonNode node = mapper.readTree(json);

        assertThatThrownBy(() -> JsonRpcRequest.of(node))
                .isInstanceOf(JsonProcessingException.class);
    }
}
