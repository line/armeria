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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcNotification;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcStreamableResponse;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.sse.ServerSentEventBuilder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.test.StepVerifier;

class JsonRpcStreamTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final JsonRpcService jsonRpcService =
                    JsonRpcService.builder()
                                  .enableServerSentEvents(true)
                                  .methodHandler("stream", new MyStreamingHandler())
                                  .build();
            sb.service("/json-rpc", jsonRpcService);
        }
    };

    @Test
    void streamableTest() {
        final WebClient client = server.webClient();
        final SplitHttpResponse response =
                client.prepare()
                      .post("/json-rpc")
                      .header(HttpHeaderNames.ACCEPT, MediaType.JSON)
                      .header(HttpHeaderNames.ACCEPT, MediaType.EVENT_STREAM)
                      .contentJson(JsonRpcRequest.of(1, "stream",
                                                     ImmutableList.of("data-1", "data-2", "data-3")))
                      .execute()
                      .split();
        assertThat(response.headers().join().status()).isEqualTo(HttpStatus.OK);
        StepVerifier.create(response.body())
                    .expectNextMatches(data -> {
                        final ServerSentEvent serverSentEvent = parse(data.toStringUtf8());
                        final JsonRpcNotification noti = JsonRpcNotification.fromJson(serverSentEvent.data());
                        assertThat(noti.method()).isEqualTo("streamData");
                        assertThat(noti.params().asList()).containsExactly("data-1");
                        assertThat(serverSentEvent.id()).isEqualTo("msg-0");
                        assertThat(serverSentEvent.event()).isEqualTo("my-event");
                        return true;
                    })
                    .expectNextMatches(data -> {
                        final ServerSentEvent serverSentEvent = parse(data.toStringUtf8());
                        final JsonRpcNotification noti = JsonRpcNotification.fromJson(serverSentEvent.data());
                        assertThat(noti.method()).isEqualTo("streamData");
                        assertThat(noti.params().asList()).containsExactly("data-2");
                        assertThat(serverSentEvent.id()).isEqualTo("msg-1");
                        assertThat(serverSentEvent.event()).isEqualTo("my-event");
                        return true;
                    })
                    .expectNextMatches(data -> {
                        final ServerSentEvent serverSentEvent = parse(data.toStringUtf8());
                        final JsonRpcNotification noti = JsonRpcNotification.fromJson(serverSentEvent.data());
                        assertThat(noti.method()).isEqualTo("streamData");
                        assertThat(noti.params().asList()).containsExactly("data-3");
                        assertThat(serverSentEvent.id()).isEqualTo("msg-2");
                        assertThat(serverSentEvent.event()).isEqualTo("my-event");
                        return true;
                    })
                    .expectNextMatches(data -> {
                        final ServerSentEvent serverSentEvent = parse(data.toStringUtf8());
                        final JsonRpcResponse res = JsonRpcResponse.fromJson(serverSentEvent.data());
                        assertThat(res.result()).isEqualTo("streamEnd");
                        assertThat(res.id()).isEqualTo(1);
                        assertThat(serverSentEvent.id()).isEqualTo("final");
                        assertThat(serverSentEvent.event()).isEqualTo("my-event");
                        return true;
                    })
                    .expectNext(HttpData.empty())
                    .verifyComplete();
    }

    // TODO(ikhoon): Add more tests such as error handling, cancellation, etc.

    private static final class MyStreamingHandler implements JsonRpcMethodHandler {

        @Override
        public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                final JsonRpcStreamableResponse stream = JsonRpcResponse.streaming();
                List<Object> asList = request.params().asList();
                for (int i = 0; i < asList.size(); i++) {
                    final Object param = asList.get(i);
                    stream.write(JsonRpcNotification.of("streamData", ImmutableList.of(param)),
                                 "msg-" + i, "my-event");
                }
                stream.close(JsonRpcResponse.ofSuccess("streamEnd"), "final", "my-event");
                return stream;
            });
        }
    }

    private static ServerSentEvent parse(String data) {
        // TODO(ikhoon): Implement a proper parser in ServerSentEvent.
        final String[] lines = data.split("\n");
        final ServerSentEventBuilder sseBuilder = ServerSentEvent.builder();
        for (String line : lines) {
            if (line.startsWith("id:")) {
                sseBuilder.id(line.substring(3).trim());
            } else if (line.startsWith("event:")) {
                sseBuilder.event(line.substring(6).trim());
            } else if (line.startsWith("data:")) {
                sseBuilder.data(line.substring(5).trim());
            }
        }
        return sseBuilder.build();
    }
}
