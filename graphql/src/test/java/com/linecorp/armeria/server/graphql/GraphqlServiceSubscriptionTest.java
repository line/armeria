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

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import graphql.schema.DataFetcher;
import graphql.schema.StaticDataFetcher;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;

import java.io.File;
import java.net.URISyntaxException;

import static org.assertj.core.api.Assertions.assertThat;

class GraphqlServiceSubscriptionTest {
    final File graphqlSchemaFile;

    {
        try {
            graphqlSchemaFile = new File(getClass().getResource("/testing/graphql/subscription.graphqls").toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    final GraphqlService service =
        GraphqlService.builder()
            .schemaFile(graphqlSchemaFile)
            .useWebSocket(true)
            .runtimeWiring(c -> {
                final StaticDataFetcher bar = new StaticDataFetcher("bar");
                c.type("Query",
                    typeWiring -> typeWiring.dataFetcher("foo", bar));
                c.type("Subscription",
                    typeWiring -> typeWiring.dataFetcher("hello", dataFetcher()));
            })
            .build();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/testing/graphql/subscription.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .useWebSocket(true)
                                  .runtimeWiring(c -> {
                                      final StaticDataFetcher bar = new StaticDataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                      c.type("Subscription",
                                             typeWiring -> typeWiring.dataFetcher("hello", dataFetcher()));
                                  })
                                  .build();
            sb.service("/graphql", service);
        }
    };

    private static DataFetcher<Publisher<String>> dataFetcher() {
        return environment -> StreamMessage.of("Armeria");
    }

    private HttpRequestWriter req;
    private ServiceRequestContext ctx;

    @BeforeEach
    void setUp() {
        req = HttpRequest.streaming(webSocketUpgradeHeaders());
        ctx = ServiceRequestContext.builder(req)
            .sessionProtocol(SessionProtocol.H1C)
            .build();
    }

    private static RequestHeaders webSocketUpgradeHeaders() {
        return RequestHeaders.builder(HttpMethod.GET, "/chat")
            .add(HttpHeaderNames.CONNECTION,
                HttpHeaderValues.UPGRADE.toString() + ',' +
                    // It works even if the header contains multiple values
                    HttpHeaderValues.KEEP_ALIVE)
            .add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET +
                ", additional_value")
            .add(HttpHeaderNames.HOST, "foo.com")
            .add(HttpHeaderNames.ORIGIN, "http://foo.com")
            .addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
            .add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
            .add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "superchat")
            .build();
    }

    @Test
    void testSubscriptionViaHttp() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "subscription {hello}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);

    private HttpData toHttpData(WebSocketFrame frame) {
        return HttpData.wrap(encoder.encode(ctx, frame));
    }

    @Test
    void testSubscriptionViaWebSocket() throws Exception {
        final HttpResponse response = service.serve(ctx, req);

        req.write(toHttpData(WebSocketFrame.ofText("{\"type\": \"connection_init\"}", false)));
        req.write(toHttpData(WebSocketFrame.ofText("{\"type\": \"ping\"}", false)));
        req.close();

        response.subscribe(NoopSubscriber.get());
    }
}
