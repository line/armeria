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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestOptions;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.concurrent.EventExecutor;

class Http1ConnectionReuseTest {

    private static final List<SocketAddress> remoteAddresses = new ArrayList<>(3);

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

        final WebClient webClient = WebClient.of(server.uri(SessionProtocol.H1C));
        final AggregatedHttpResponse res = webClient.execute(httpRequest).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(webClient.get("/").aggregate().join().status()).isSameAs(HttpStatus.OK);
        future.completeValue(null); // This will make the first connection return to the pool.
        assertThat(webClient.get("/").aggregate().join().status()).isSameAs(HttpStatus.OK);
        assertThat(remoteAddresses.get(0)).isNotSameAs(remoteAddresses.get(1));
        assertThat(remoteAddresses.get(0)).isSameAs(remoteAddresses.get(2));
    }

    private static HttpRequest httpRequest(CompletableFuture<Void> future) {
        return new HttpRequest() {
            @Override
            public RequestHeaders headers() {
                return RequestHeaders.of(HttpMethod.GET, "/");
            }

            @Override
            public RequestOptions options() {
                return RequestOptions.of();
            }

            @Override
            public boolean isOpen() {
                return false;
            }

            @Override
            public boolean isEmpty() {
                return true;
            }

            @Override
            public long demand() {
                return 0;
            }

            @Override
            public CompletableFuture<Void> whenComplete() {
                return future;
            }

            @Override
            public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor) {}

            @Override
            public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                                  SubscriptionOption... options) {
                final Subscription subscription = new Subscription() {
                    @Override
                    public void request(long n) {}

                    @Override
                    public void cancel() {}
                };
                executor.execute(() -> subscriber.onSubscribe(subscription));
                executor.execute(subscriber::onComplete);
            }

            @Override
            public void abort() {}

            @Override
            public void abort(Throwable cause) {}
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
