/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceTest.validateContextAndRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceExceptionHandlerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyService1())
              .annotatedService("/2", new MyService2())
              .annotatedService("/3", new MyService3())
              .annotatedService("/4", new MyService4())
              .annotatedService("/5", new MyService5());

            sb.decorator(LoggingService.newDecorator())
              .requestTimeoutMillis(500L);
        }
    };

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void sync() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/sync"))
                                                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler1");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void async() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/async"))
                                                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler1");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void resp1() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/resp1"))
                                                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler1");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void resp2() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/1/resp2")).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler2");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void sync2() {
        // By default exception handler.
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/2/sync")).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void async2() {
        // The method returns CompletionStage<?>. It throws an exception immediately if it is called.
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/2/async")).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void async2Aggregation() {
        // The method returns CompletionStage<?>. It throws an exception after the request is aggregated.
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/2/async/aggregation"))
                      .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bad1() {
        // Timeout because of bad exception handler.
        // As a response headers was written already, RST_STREAM will be received.
        assertThatThrownBy(
                () -> client.execute(RequestHeaders.of(HttpMethod.GET, "/3/bad1")).aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(ClosedStreamException.class)
                .hasRootCauseMessage("received a RST_STREAM frame: INTERNAL_ERROR");
    }

    @Test
    void bad2() {
        // Internal server error would be returned due to invalid response.
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/3/bad2"))
                                                      .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void bad3() {
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/3/bad3"))
                                                      .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void handler3() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/4/handler3"))
                                                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler3");
        assertThat(NoExceptionHandler.counter.get()).isZero();
    }

    @Test
    void handle3WithBadDecorator() {
        // A decorator throws an exception.
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/5/handler3"))
                                                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    @ExceptionHandler(NoExceptionHandler.class)
    @ExceptionHandler(AnticipatedExceptionHandler1.class)
    public static class MyService1 {

        @Get("/sync")
        public String sync(ServiceRequestContext ctx, HttpRequest req) {
            throw new AnticipatedException("Oops!");
        }

        @Get("/async")
        public CompletionStage<String> async(ServiceRequestContext ctx, HttpRequest req) {
            return completeExceptionallyLater(ctx);
        }

        @Get("/resp1")
        public HttpResponse httpResponse(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(raiseExceptionImmediately());
        }

        @Get("/resp2")
        @ExceptionHandler(NoExceptionHandler.class)
        @ExceptionHandler(AnticipatedExceptionHandler2.class)
        public HttpResponse asyncHttpResponse(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(completeExceptionallyLater(ctx));
        }
    }

    // No exception handler is specified.
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyService2 {

        @Get("/sync")
        public String sync(ServiceRequestContext ctx, HttpRequest req) {
            throw new IllegalArgumentException("Oops!");
        }

        @Get("/async")
        public CompletionStage<String> async(ServiceRequestContext ctx,
                                             HttpRequest req) {
            // Throw an exception immediately if this method is invoked.
            throw new IllegalArgumentException("Oops!");
        }

        @Get("/async/aggregation")
        public CompletionStage<String> async(ServiceRequestContext ctx,
                                             AggregatedHttpRequest req) {
            // Aggregate the request then throw an exception.
            throw new IllegalArgumentException("Oops!");
        }
    }

    @ResponseConverter(UnformattedStringConverterFunction.class)
    @ExceptionHandler(BadExceptionHandler1.class)
    public static class MyService3 {

        @Get("/bad1")
        public HttpResponse bad1(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(completeExceptionallyLater(ctx));
        }

        @Get("/bad2")
        @ExceptionHandler(BadExceptionHandler2.class)
        public HttpResponse bad2(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(completeExceptionallyLater(ctx));
        }

        @Get("/bad3")
        @ExceptionHandler(BadExceptionHandler3.class)
        public HttpResponse bad3(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(completeExceptionallyLater(ctx));
        }
    }

    @ExceptionHandler(AnticipatedExceptionHandler3.class)
    public static class MyService4 extends MyService1 {
        @Get("/handler3")
        public HttpResponse handler3(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(completeExceptionallyLater(ctx));
        }
    }

    @LoggingDecorator
    @ExceptionHandler(AnticipatedExceptionHandler3.class)
    public static class MyService5 extends MyService1 {
        @Get("/handler3")
        @Decorator(ExceptionThrowingDecorator.class)
        public HttpResponse handler3(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    public static final class ExceptionThrowingDecorator implements DecoratingHttpServiceFunction {

        @Override
        public HttpResponse serve(
                HttpService delegate, ServiceRequestContext ctx, HttpRequest req) throws Exception {
            validateContextAndRequest(ctx, req);
            throw new AnticipatedException();
        }
    }

    private static <T> CompletionStage<T> raiseExceptionImmediately() {
        final CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new AnticipatedException("Oops!"));
        return future;
    }

    private static <T> CompletionStage<T> completeExceptionallyLater(ServiceRequestContext ctx) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        // Execute 100 ms later.
        ctx.eventLoop().schedule(
                () -> future.completeExceptionally(new AnticipatedException("Oops!")),
                100, TimeUnit.MILLISECONDS);
        return future;
    }

    static class NoExceptionHandler implements ExceptionHandlerFunction {
        static final AtomicInteger counter = new AtomicInteger();

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            // Not accept any exception. But should be called this method.
            counter.incrementAndGet();
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    static class AnticipatedExceptionHandler1 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "handler1");
        }
    }

    static class AnticipatedExceptionHandler2 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "handler2");
        }
    }

    static class AnticipatedExceptionHandler3 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "handler3");
        }
    }

    static class BadExceptionHandler1 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            final HttpResponseWriter response = HttpResponse.streaming();
            response.write(ResponseHeaders.of(HttpStatus.OK));
            // Timeout may occur before responding.
            ctx.eventLoop().schedule((Runnable) response::close, 10, TimeUnit.SECONDS);
            return response;
        }
    }

    static class BadExceptionHandler2 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            final HttpResponseWriter response = HttpResponse.streaming();
            // Make invalid response.
            response.write(HttpStatus.OK.toHttpData());
            response.close();
            return response;
        }
    }

    static class BadExceptionHandler3 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            final HttpResponseWriter response = HttpResponse.streaming();
            // Timeout may occur before responding.
            ctx.eventLoop().schedule((Runnable) response::close, 10, TimeUnit.SECONDS);
            return response;
        }
    }
}
