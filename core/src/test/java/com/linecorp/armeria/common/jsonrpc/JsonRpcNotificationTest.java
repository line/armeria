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

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

class JsonRpcNotificationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testEquals() {
        final JsonRpcNotification noti0 = JsonRpcNotification.of("store", ImmutableMap.of("key", "value"));
        final JsonRpcNotification noti1 = JsonRpcNotification.of("store", ImmutableMap.of("key", "value"));
        assertThat(noti0).isEqualTo(noti1) ;

        final JsonRpcNotification noti2 = JsonRpcNotification.of("put", ImmutableList.of("a", "b"));
        final JsonRpcNotification noti3 = JsonRpcNotification.of("put", ImmutableList.of("a", "b"));
        assertThat(noti2).isEqualTo(noti3);
    }
    
    @Test
    void positional() {
        final JsonRpcNotification noti = JsonRpcNotification.of("update", ImmutableList.of(1, 2, 3, 4, 5));

        assertThat(noti.method()).isEqualTo("update");
        assertThat(noti.params().isPositional()).isTrue();
        assertThat(noti.params().asList()).containsExactly(1, 2, 3, 4, 5);
        assertThat(noti.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void named() {
        final JsonRpcNotification noti = JsonRpcNotification.of("update", ImmutableMap.of("foo", "bar"));

        assertThat(noti.method()).isEqualTo("update");
        assertThat(noti.params().isNamed()).isTrue();
        assertThat(noti.params().asMap()).containsExactly(Maps.immutableEntry("foo", "bar"));
        assertThat(noti.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void fromJson_notification() throws JsonProcessingException {
        final String json = "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [1,2,3,4,5]}";
        final JsonRpcNotification noti = JsonRpcNotification.fromJson(json);

        assertThat(noti.method()).isEqualTo("update");
        assertThat(noti.params().isPositional()).isTrue();
        assertThat(noti.params().asList()).containsExactly(1, 2, 3, 4, 5);
        assertThat(noti.version()).isEqualTo(JsonRpcVersion.JSON_RPC_2_0);
    }

    @Test
    void serializePositional() throws JsonProcessingException {
        final JsonRpcNotification noti = JsonRpcNotification.of("subtract", ImmutableList.of(42, 23));
        final String json = mapper.writeValueAsString(noti);

        assertThatJson(json).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"method\":\"subtract\",\"params\":[42,23]}");
    }

    @Test
    void serializeNamed() throws JsonProcessingException {
        final JsonRpcNotification noti = JsonRpcNotification.of("subtract", ImmutableMap.of("foo", "bar"));
        final String json = mapper.writeValueAsString(noti);
        assertThatJson(json).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"method\":\"subtract\",\"params\":{\"foo\":\"bar\"}}");
    }
}
