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
import org.junit.Ignore;
import org.junit.Test;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import brave.Tracing;
import brave.http.HttpTracing;
import brave.propagation.StrictScopeDecorator;
import brave.sampler.Sampler;
import brave.test.http.ITHttpServer;

public class BraveServiceIntegrationTest extends ITHttpServer {

    @Nullable
    private Server server;
    @Nullable
    final ListeningExecutorService executorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));

    private final StrictScopeDecorator strictScopeDecorator = StrictScopeDecorator.create();

    public BraveServiceIntegrationTest() {
        this.currentTraceContext = RequestContextCurrentTraceContext.builder()
            .addScopeDecorator(strictScopeDecorator)
            .build();
        this.tracing = tracingBuilder(Sampler.ALWAYS_SAMPLE).build();
        this.httpTracing = HttpTracing.create(tracing);
    }

    @Override
    protected void checkForLeakedScopes() {
        strictScopeDecorator.close();
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
        sb.service("/async", (ctx, req) -> asyncResponse(future ->
            future.complete(HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "bar"))));

        sb.service("/exception", (ctx, req) -> {
            // TODO: HttpResponseException.of(status, "not ready")
            throw HttpResponseException.of(HttpStatus.SERVICE_UNAVAILABLE);
        });
        sb.service("/exceptionAsync", (ctx, req) -> asyncResponse(future ->
            // TODO: HttpResponseException.of(status, "not ready")
            future.completeExceptionally(HttpResponseException.of(HttpStatus.SERVICE_UNAVAILABLE))));

        sb.service("/items/:itemId",
                   (ctx, req) -> HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8,
                                                 String.valueOf(ctx.pathParam("itemId"))));
        sb.service("/async_items/:itemId", (ctx, req) -> asyncResponse(future ->
            future.complete(HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8,
                                                String.valueOf(ctx.pathParam("itemId"))))));
        // TODO: how do we mount "/items/:itemId" under the prefix "/nested"?

        // TODO: these fail because the context isn't here for some reason?
        sb.service("/child", (ctx, req) -> {
            tracing.tracer().nextSpan().name("child").start().finish();
            return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "happy");
        });
        sb.service("/baggage", (ctx, req) -> {
            String value = String.valueOf(BAGGAGE_FIELD.getValue());
            return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, value);
        });

        sb.service("/extra",
            (ctx, req) -> HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8,
                String.valueOf(req.headers().get(BAGGAGE_FIELD.name()))));
        sb.service("/badrequest", (ctx, req) -> HttpResponse.of(BAD_REQUEST));
        sb.service("/child", (ctx, req) -> {
            Tracing.currentTracer().nextSpan().name("child").start().finish();
            return HttpResponse.of(OK, MediaType.PLAIN_TEXT_UTF_8, "happy");
        });

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
    @Ignore("TODO: /child is not using request scope?")
    @Override
    public void createsChildSpan() {
    }

    @Test
    @Ignore("TODO: /child is not using request scope?")
    @Override
    public void childCompletesBeforeServer() {
    }

    @Test
    @Ignore("TODO: /baggage is not using request scope?")
    @Override
    public void readsBaggage_newTrace() {
    }

    @Test
    @Ignore("TODO: /baggage is not using request scope?")
    @Override
    public void readsBaggage_unsampled() {
    }

    @Test
    @Ignore("TODO: /baggage is not using request scope?")
    @Override
    public void readsBaggage_existingTrace() {
    }

    @Test
    @Override
    public void notFound() {
        throw new AssumptionViolatedException("Armeria cannot decorate a non-existent path mapping.");
    }

    @Test
    @Override
    public void errorTag_exceptionOverridesHttpStatus() {
        throw new AssumptionViolatedException("TODO: HttpResponseException cannot set message");
    }

    @Test
    @Override
    public void errorTag_exceptionOverridesHttpStatus_async() {
        throw new AssumptionViolatedException("TODO: HttpResponseException cannot set message");
    }

    @Test
    @Override
    public void finishedSpanHandlerSeesException() {
        throw new AssumptionViolatedException("TODO: HttpResponseException cannot set message");
    }

    @Test
    @Override
    public void finishedSpanHandlerSeesException_async() {
        throw new AssumptionViolatedException("TODO: HttpResponseException cannot set message");
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
