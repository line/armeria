/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.graphql;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;
import graphql.schema.StaticDataFetcher;

class GraphqlServiceSubscriptionTest {

    private static AtomicReference<StreamMessage<String>> streamRef;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/testing/graphql/subscription.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .enableWebSocket(true)
                                  .runtimeWiring(c -> {
                                      final StaticDataFetcher bar = new StaticDataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                      c.type("Subscription",
                                             typeWiring -> typeWiring.dataFetcher("hello", dataFetcher())
                                                                     .dataFetcher("bye", notCompleting()));
                                  })
                                  .build();
            sb.service("/graphql", service);
        }
    };

    private static DataFetcher<Publisher<String>> dataFetcher() {
        return environment -> StreamMessage.of("Armeria");
    }

    private static DataFetcher<Publisher<String>> notCompleting() {
        return environment -> streamRef.get();
    }

    @BeforeEach
    void beforeEach() {
         streamRef = new AtomicReference<>(StreamMessage.streaming());
    }

    @Test
    void testSubscriptionOverHttp() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "subscription {hello}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThatJson(response.contentUtf8())
                .node("errors[0].message")
                .isEqualTo("Use GraphQL over WebSocket for subscription");
    }

    @Test
    void testSubscriptionOverWebSocketHttp1() {
        testWebSocket(SessionProtocol.H1C);
    }

    @Test
    void testSubscriptionOverWebSocketHttp2() {
        testWebSocket(SessionProtocol.H2C);
    }

    private void testWebSocket(SessionProtocol sessionProtocol) {
        final WebSocketClient webSocketClient =
                WebSocketClient.builder(server.uri(sessionProtocol, SerializationFormat.WS))
                               .subprotocols("graphql-transport-ws")
                               .build();
        final CompletableFuture<WebSocketSession> future = webSocketClient.connect("/graphql");

        final WebSocketSession webSocketSession = future.join();

        final WebSocketWriter outbound = webSocketSession.outbound();

        final List<String> receivedEvents = new ArrayList<>();
        //noinspection ReactiveStreamsSubscriberImplementation
        webSocketSession.inbound().subscribe(new Subscriber<WebSocketFrame>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WebSocketFrame webSocketFrame) {
                if (webSocketFrame.type() == WebSocketFrameType.TEXT) {
                    receivedEvents.add(webSocketFrame.text());
                }
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });

        outbound.write("{\"type\":\"ping\"}");
        outbound.write("{\"type\":\"connection_init\"}");
        outbound.write(
                "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription {hello}\"}}");

        await().until(() -> receivedEvents.size() >= 3);
        assertThatJson(receivedEvents.get(0)).node("type").isEqualTo("pong");
        assertThatJson(receivedEvents.get(1)).node("type").isEqualTo("connection_ack");
        assertThatJson(receivedEvents.get(2))
                .node("type").isEqualTo("next")
                .node("id").isEqualTo("\"1\"")
                .node("payload.data.hello").isEqualTo("Armeria");
    }

    @Test
    void testSubscriptionCleanedUpWhenClosed() throws Exception {
        final WebSocketClient webSocketClient =
                WebSocketClient.builder(server.uri(SessionProtocol.H1C, SerializationFormat.WS))
                               .subprotocols("graphql-transport-ws")
                               .build();
        final CompletableFuture<WebSocketSession> future = webSocketClient.connect("/graphql");

        final WebSocketSession webSocketSession = future.join();

        final WebSocketWriter outbound = webSocketSession.outbound();

        final List<String> receivedEvents = new ArrayList<>();
        //noinspection ReactiveStreamsSubscriberImplementation
        webSocketSession.inbound().subscribe(new Subscriber<WebSocketFrame>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WebSocketFrame webSocketFrame) {
                if (webSocketFrame.type() == WebSocketFrameType.TEXT) {
                    receivedEvents.add(webSocketFrame.text());
                }
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });

        outbound.write("{\"type\":\"ping\"}");
        outbound.write("{\"type\":\"connection_init\"}");
        outbound.write(
                "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription {bye}\"}}");
        // wait until the streamRef is subscribed
        await().untilAsserted(() -> assertThat(streamRef.get().demand()).isGreaterThan(0));
        outbound.close();

        await().untilAsserted(() -> assertThat(streamRef.get().whenComplete()).isDone());
        assertThatThrownBy(() -> streamRef.get().whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CancelledSubscriptionException.class);
    }
}
