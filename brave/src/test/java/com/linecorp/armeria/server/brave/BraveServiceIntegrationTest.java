/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.brave;

import static com.linecorp.armeria.common.HttpStatus.BAD_REQUEST;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Test;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import brave.propagation.CurrentTraceContext;
import brave.test.http.ITHttpServer;

public class BraveServiceIntegrationTest extends ITHttpServer {

    @Nullable
    private Server server;
    @Nullable
    final ListeningExecutorService executorService =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));

    @Override
    protected CurrentTraceContext.Builder currentTraceContextBuilder() {
        return RequestContextCurrentTraceContext.builder();
    }

    @Override
    protected void init() {
        final ServerBuilder sb = Server.builder();
        sb.service("/", (ctx, req) -> {
            if (req.method() == HttpMethod.OPTIONS) {
                return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "");
            }
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        });
        sb.service("/foo", (ctx, req) -> HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "bar"));
        sb.service("/async", (ctx, req) -> asyncResponse(future -> {
            future.complete(HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "bar"));
        }));

        sb.service("/exception", (ctx, req) -> {
            throw new IllegalStateException("not ready");
        });
        sb.service("/exceptionAsync", (ctx, req) -> asyncResponse(future -> {
            future.completeExceptionally(new IllegalStateException("not ready"));
        }));

        sb.service("/items/:itemId",
                   (ctx, req) -> HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8,
                                                 String.valueOf(ctx.pathParam("itemId"))));
        sb.service("/async_items/:itemId", (ctx, req) -> asyncResponse(future -> {
            future.complete(HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8,
                                            String.valueOf(ctx.pathParam("itemId"))));
        }));
        // "/nested" left out as there's no sub-routing feature at the moment.

        sb.service("/child", (ctx, req) -> {
            tracing.tracer().nextSpan().name("child").start().finish();
            return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "happy");
        });
        sb.service("/baggage", (ctx, req) -> {
            final String value = String.valueOf(BAGGAGE_FIELD.getValue());
            return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, value);
        });

        sb.service("/badrequest", (ctx, req) -> HttpResponse.of(BAD_REQUEST));

        sb.decorator(BraveService.newDecorator(httpTracing));

        server = sb.build();
        server.start().join();
    }

    HttpResponse asyncResponse(Consumer<CompletableFuture<HttpResponse>> completeResponse) {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture);
        executorService.submit(() -> completeResponse.accept(responseFuture));
        return res;
    }

    @Test
    @Override
    public void notFound() {
        throw new AssumptionViolatedException(
                "Armeria yields 'get /*' as a span name for a non-existent mapping.");
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop();
        }
        executorService.shutdownNow();
    }

    @Override
    protected String url(String path) {
        assertThat(server).isNotNull();
        return "http://127.0.0.1:" + server.activeLocalPort(SessionProtocol.HTTP) + path;
    }
}
