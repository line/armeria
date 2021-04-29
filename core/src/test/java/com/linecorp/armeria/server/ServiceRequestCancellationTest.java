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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
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

    @Test
    void shouldCompleteLogWhenCancelledByClient() {
        final ClientFactory factory = ClientFactory.builder().build();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(factory)
                                          .build();

        final CompletableFuture<AggregatedHttpResponse> responseFuture = client.get("/reset").aggregate();
        await().untilAtomic(ctxRef, Matchers.notNullValue());
        factory.close();
        final RequestLog log = ctxRef.get().log().whenComplete().join();

        assertThat(log.responseCause())
                .isInstanceOf(ClosedStreamException.class)
                .hasMessageContaining("received a RST_STREAM frame: CANCEL");

        assertThatThrownBy(responseFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClosedStreamException.class);
    }
}
