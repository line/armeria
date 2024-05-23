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
 * under the License
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ResponseCancellationException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServiceRequestCancellationTest {

    private static final AtomicReference<ServiceRequestContext> ctxRef = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/reset", (ctx, req) -> {
                ctxRef.set(ctx);
                return HttpResponse.streaming();
            });
        }
    };

    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    @ParameterizedTest
    void shouldCompleteLogWhenCancelledByClient(SessionProtocol protocol) {
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture;
        final ClientRequestContext clientRequestContext;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = client.get("/reset").aggregate();
            clientRequestContext = captor.get();
        }
        await().untilAtomic(ctxRef, Matchers.notNullValue());
        clientRequestContext.cancel();
        final RequestLog log = ctxRef.get().log().whenComplete().join();

        if (protocol.isMultiplex()) {
            assertThat(log.responseCause())
                    .isInstanceOf(ClosedStreamException.class)
                    .hasMessageContaining("received a RST_STREAM frame: CANCEL");

            assertThatThrownBy(responseFuture::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseCancellationException.class);
        } else {
            assertThat(log.responseCause())
                    .isInstanceOf(ClosedSessionException.class);

            assertThatThrownBy(responseFuture::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseCancellationException.class);
        }

        ctxRef.set(null);
    }
}
