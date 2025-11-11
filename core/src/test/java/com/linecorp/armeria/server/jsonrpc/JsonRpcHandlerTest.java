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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcNotification;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JsonRpcHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final JsonRpcService jsonRpcService =
                    JsonRpcService.builder()
                            .methodHandler("echo", (ctx, req) ->
                                    CompletableFuture.completedFuture(
                                            JsonRpcResponse.ofSuccess(req.id(), req.params())))
                                  .build();
            sb.service("/json-rpc", jsonRpcService);
        }
    };

    private BlockingWebClient client;

    @BeforeEach
    void setUp() {
        client = server.blockingWebClient();
    }

    @Test
    void testEcho() {
        final JsonRpcRequest req = JsonRpcRequest.of(1, "echo", ImmutableList.of(1, 2));

        final ResponseEntity<JsonRpcResponse> response = execute(req);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(1);
        assertThat(rpcResponse.isSuccess()).isTrue();
        assertThat((List<Integer>) rpcResponse.result()).containsExactly(1, 2);
    }

    @Test
    void handleUnknownMethod() {
        final JsonRpcRequest req = JsonRpcRequest.of(123, "Unknown");

        final ResponseEntity<JsonRpcResponse> response = execute(req);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        final JsonRpcResponse rpcResponse = response.content();
        assertThat(rpcResponse.id()).isEqualTo(123);
        assertThat(rpcResponse.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND.code());
    }

    @Test
    void handleNotification() {
        final JsonRpcNotification req = JsonRpcNotification.of("Unknown");
        final AggregatedHttpResponse response = client.prepare()
                                                      .post("/json-rpc")
                                                      .contentJson(req)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.content().isEmpty()).isTrue();
    }

    @Test
    void handleRpcResponseInput() {
        // MCP allows a response as an input, and it should be accepted as a notification.
        final JsonRpcResponse req = JsonRpcResponse.ofSuccess("Input");
        final AggregatedHttpResponse response = client.prepare()
                                                      .post("/json-rpc")
                                                      .contentJson(req)
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.content().isEmpty()).isTrue();
    }

    private ResponseEntity<JsonRpcResponse> execute(JsonRpcMessage request) {
        return client.prepare()
                     .post("/json-rpc")
                     .contentJson(request)
                     .asJson(JsonRpcResponse.class, status -> true)
                     .execute();
    }
}
