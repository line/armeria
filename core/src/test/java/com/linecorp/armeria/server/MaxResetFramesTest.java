/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.CountingConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MaxResetFramesTest {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.idleTimeoutMillis(0);
            sb.http2MaxResetFramesPerWindow(10, 60);
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(req.aggregate().thenApply(unused -> HttpResponse.of(200)));
            });
        }
    };

    @Test
    void shouldCloseConnectionWhenExceedingMaxResetFrames() {
        final CountingConnectionPoolListener listener = new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(listener)
                                                  .idleTimeoutMillis(0)
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                              .factory(factory)
                                              .build();
            final List<CompletableFuture<AggregatedHttpResponse>> futures =
                    IntStream.range(0, 11)
                             .mapToObj(unused -> HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                                StreamMessage.of(InvalidHttpObject.INSTANCE)))
                             .map(client::execute)
                             .map(HttpResponse::aggregate)
                             .collect(toImmutableList());

            CompletableFutures.successfulAsList(futures, cause -> null).join();
            assertThat(listener.opened()).isEqualTo(1);
            await().untilAsserted(() -> assertThat(listener.closed()).isEqualTo(1));
        }
    }

    /**
     * {@link WebClient} resets a stream when it receives an invalid {@link HttpObject} from
     * {@link HttpRequest}.
     */
    private enum InvalidHttpObject implements HttpObject {

        INSTANCE;

        @Override
        public boolean isEndOfStream() {
            return false;
        }
    }
}
