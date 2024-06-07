/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.traffic.ChannelTrafficShapingHandler;

class WriteTimeoutTest {

    private static final ClientFactory clientFactory = ClientFactory
            .builder()
            .option(ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER, pipeline -> {
                pipeline.addLast(new ChannelTrafficShapingHandler(1024, 0)); // write 1 KB / sec
            })
            .build();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(Route.ofCatchAll(), (ctx, req) -> HttpResponse.of(200));
        }
    };

    @AfterAll
    static void afterAll() {
        clientFactory.close();
    }

    @Test
    void testWriteTimeout() {
        final RequestHeadersBuilder headersBuilder = RequestHeaders.builder(HttpMethod.GET, "/");
        headersBuilder.add("header1", Strings.repeat("a", 2048)); // set a header over 1KB

        // using h1c since http2 compresses headers
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HttpResponse res = WebClient.builder(SessionProtocol.H1C, server.httpEndpoint())
                                              .factory(clientFactory)
                                              .writeTimeoutMillis(1000)
                                              .build()
                                              .execute(headersBuilder.build(), "content");
            final ClientRequestContext ctx = captor.get();
            assertThatThrownBy(() -> res.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(WriteTimeoutException.class);

            final RequestLog log = ctx.log().whenComplete().join();
            // Make sure that the session is deactivated after the write timeout.
            assertThat(HttpSession.get(log.channel()).isAcquirable()).isFalse();
        }
    }
}
