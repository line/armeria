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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonRpcResponseTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void withObject() throws JsonProcessingException {
        final Object result = "hello";
        final JsonRpcResponse res = JsonRpcResponse.ofSuccess(result);

        assertThat(res.id()).isNull();
        assertThat(res.result()).isEqualTo(result);
        assertThat(res.error()).isNull();
        final String json = mapper.writeValueAsString(res);
        assertThatJson(json).isEqualTo("{\"jsonrpc\":\"2.0\",\"result\":\"hello\"}");
    }

    @Test
    void withObjectId() throws JsonProcessingException {
        final Object result = "hello";
        final JsonRpcResponse res = JsonRpcResponse.ofSuccess("my-id", result);

        assertThat(res.id()).isEqualTo("my-id");
        assertThat(res.result()).isEqualTo(result);
        assertThat(res.error()).isNull();
        final String json = mapper.writeValueAsString(res);
        assertThatJson(json).isEqualTo("{\"id\":\"my-id\",\"jsonrpc\":\"2.0\",\"result\":\"hello\"}");
    }

    @Test
    void invalidObjectType() {
        assertThatThrownBy(() -> JsonRpcResponse.ofSuccess(JsonRpcError.INVALID_PARAMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        String.format("result.class: %s (expected: not JsonRpcError)", JsonRpcError.class));
    }

    @Test
    void withJsonRpcError() throws JsonProcessingException {
        final JsonRpcError error = JsonRpcError.INTERNAL_ERROR.withData("invalid status");
        final JsonRpcResponse res = JsonRpcResponse.ofFailure(error);

        assertThat(res.result()).isNull();
        assertThat(res.error()).isEqualTo(error);

        final String json = mapper.writeValueAsString(res);
        assertThatJson(json).isEqualTo(
                "{\"error\":{\"code\":-32603,\"message\":\"Internal error\","
                + "\"data\":\"invalid status\"},\"jsonrpc\":\"2.0\"}");
    }

    @Test
    void withJsonRpcErrorId() throws JsonProcessingException {
        final JsonRpcError error = JsonRpcError.INTERNAL_ERROR.withData("invalid status");
        final JsonRpcResponse res = JsonRpcResponse.ofFailure("my-id", error);

        assertThat(res.id()).isEqualTo("my-id");
        assertThat(res.result()).isNull();
        assertThat(res.error()).isEqualTo(error);

        final String json = mapper.writeValueAsString(res);
        assertThatJson(json).isEqualTo(
                "{\"id\":\"my-id\",\"error\":{\"code\":-32603,\"message\":\"Internal error\","
                + "\"data\":\"invalid status\"},\"jsonrpc\":\"2.0\"}");
    }

    @Test
    void withId() {
        final Object result = "hello";
        final JsonRpcResponse res = JsonRpcResponse.ofSuccess(result);
        assertThat(res.id()).isNull();
        final JsonRpcResponse resWithId = res.withId("id");
        assertThat(resWithId.id()).isEqualTo("id");
        assertThat(res.id()).isNull();
    }
}
