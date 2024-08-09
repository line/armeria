/*
 * Copyright 2020 LINE Corporation
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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class Http1ConnectionReuseTest {

    private static final List<InetSocketAddress> remoteAddresses = new ArrayList<>(3);
    private static final HttpRequest REQUEST = HttpRequest.of(HttpMethod.GET, "/");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                remoteAddresses.add(ctx.remoteAddress());
                return HttpResponse.of(200);
            });
        }
    };

    @Test
    void returnToThePoolAfterRequestIsComplete() {
        final CompleteInterceptableFuture<Void> future = new CompleteInterceptableFuture<>();
        final HttpRequest httpRequest = httpRequest(future);

        final BlockingWebClient webClient = BlockingWebClient.of(server.uri(SessionProtocol.H1C));
        final AggregatedHttpResponse res = webClient.execute(httpRequest);
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(webClient.get("/").status()).isSameAs(HttpStatus.OK);
        future.completeValue(null); // This will make the first connection return to the pool.
        assertThat(webClient.get("/").status()).isSameAs(HttpStatus.OK);
        assertThat(remoteAddresses.get(0)).isNotSameAs(remoteAddresses.get(1));
        assertThat(remoteAddresses.get(0)).isSameAs(remoteAddresses.get(2));
    }

    private static HttpRequest httpRequest(CompletableFuture<Void> future) {
        return new DelegatingHttpRequest(REQUEST) {
            @Override
            public CompletableFuture<Void> whenComplete() {
                return future;
            }
        };
    }

    private static class CompleteInterceptableFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean complete(T value) {
            return false;
        }

        void completeValue(T value) {
            super.complete(value);
        }
    }
}
