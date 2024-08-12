/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TimeoutModeTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @Test
    void timeoutMode_requestStart() {
        final HttpResponse res = server
                .webClient(cb -> {
                    cb.responseTimeoutMode(ResponseTimeoutMode.FROM_START);
                    cb.responseTimeoutMillis(50);
                    cb.decorator((delegate, ctx, req) -> {
                        final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                        CommonPools.workerGroup().schedule(() -> f.complete(delegate.execute(ctx, req)),
                                                           100, TimeUnit.MILLISECONDS);
                        return HttpResponse.of(f);
                    });
                })
                .get("/");
        assertThatThrownBy(() -> res.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(ResponseTimeoutException.class);
    }

    @Test
    void timeoutMode_requestWrite() {
        final HttpRequestWriter streaming = HttpRequest.streaming(HttpMethod.POST, "/");
        final HttpResponse res = server
                .webClient(cb -> {
                    cb.responseTimeoutMode(ResponseTimeoutMode.CONNECTION_ACQUIRED);
                    cb.responseTimeout(Duration.ofMillis(50));
                })
                .execute(streaming);
        assertThatThrownBy(() -> res.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
    }

    @Test
    void timeoutMode_responseWrite() {
        final HttpResponse res = server
                .webClient(cb -> {
                    cb.responseTimeoutMode(ResponseTimeoutMode.REQUEST_SENT);
                    cb.responseTimeout(Duration.ofMillis(50));
                })
                .get("/");
        assertThatThrownBy(() -> res.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
    }

    @Test
    void timeoutMode_requestOptions() {
        final HttpResponse res = server
                .webClient(cb -> {
                    cb.responseTimeoutMode(ResponseTimeoutMode.REQUEST_SENT);
                    cb.responseTimeoutMillis(50);
                    cb.decorator((delegate, ctx, req) -> {
                        final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                        CommonPools.workerGroup().schedule(() -> f.complete(delegate.execute(ctx, req)),
                                                           100, TimeUnit.MILLISECONDS);
                        return HttpResponse.of(f);
                    });
                })
                .execute(HttpRequest.of(HttpMethod.GET, "/"),
                         RequestOptions.builder().responseTimeoutMode(ResponseTimeoutMode.FROM_START).build());
        assertThatThrownBy(() -> res.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(ResponseTimeoutException.class);
    }

    @Test
    void timeoutMode_transforming() {
        final HttpResponse res = server
                .webClient(cb -> {
                    cb.responseTimeoutMode(ResponseTimeoutMode.REQUEST_SENT);
                    cb.responseTimeoutMillis(50);
                    cb.decorator((delegate, ctx, req) -> {
                        final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                        CommonPools.workerGroup().schedule(() -> f.complete(delegate.execute(ctx, req)),
                                                           100, TimeUnit.MILLISECONDS);
                        return HttpResponse.of(f);
                    });
                })
                .prepare()
                .responseTimeoutMode(ResponseTimeoutMode.FROM_START)
                .get("/").execute();
        assertThatThrownBy(() -> res.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(ResponseTimeoutException.class);
    }
}
