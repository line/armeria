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

import java.lang.reflect.ParameterizedType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.BlockingWebClient;
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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExceptionHandlerTest.CompositeRequest.SimpleRequest;
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
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceExceptionHandlerTest {

    private static final RuntimeException EXCEPTION = new RuntimeException();

    private static final AtomicReference<Throwable> capturedException = new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyService1())
              .annotatedService("/2", new MyService2())
              .annotatedService("/3", new MyService3())
              .annotatedService("/4", new MyService4())
              .annotatedService("/5", new MyService5())
              .annotatedService("/6", new MyService6());

            sb.decorator(LoggingService.newDecorator())
              .requestTimeoutMillis(500L);
        }
    };

    private BlockingWebClient client;

    @BeforeEach
    void setUp() {
        client = BlockingWebClient.of(server.httpUri());
        capturedException.set(null);
    }

    @Test
    void sync() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/sync"));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler1");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void async() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/async"));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler1");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void resp1() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/resp1"));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler1");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void resp2() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/1/resp2"));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler2");
        assertThat(NoExceptionHandler.counter.get()).isEqualTo(1);
    }

    @Test
    void sync2() {
        // By default exception handler.
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/2/sync"));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void async2() {
        // The method returns CompletionStage<?>. It throws an exception immediately if it is called.
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/2/async"));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void async2Aggregation() {
        // The method returns CompletionStage<?>. It throws an exception after the request is aggregated.
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/2/async/aggregation"));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bad1() {
        // Timeout because of bad exception handler.
        // As a response headers was written already, RST_STREAM will be received.
        assertThatThrownBy(() -> client.execute(RequestHeaders.of(HttpMethod.GET, "/3/bad1")))
                .isInstanceOf(ClosedStreamException.class)
                .hasMessageContaining("received a RST_STREAM frame: INTERNAL_ERROR");
    }

    @Test
    void bad2() {
        // Internal server error would be returned due to invalid response.
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/3/bad2"));
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void bad3() {
        final AggregatedHttpResponse response = client.execute(RequestHeaders.of(HttpMethod.GET, "/3/bad3"));
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void handler3() {
        NoExceptionHandler.counter.set(0);
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/4/handler3"));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler3");
        assertThat(NoExceptionHandler.counter.get()).isZero();
    }

    @Test
    void handle3WithBadDecorator() {
        // A decorator throws an exception.
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/5/handler3"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("handler3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/6/simple", "/6/composite", "/6/throwing-setter"})
    void requestConverterExceptionIsRelayed(String path) {
        final AggregatedHttpResponse res = server.blockingWebClient().post(path, "content");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(capturedException).hasValue(EXCEPTION);
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
            return HttpResponse.of(raiseExceptionImmediately());
        }

        @Get("/resp2")
        @ExceptionHandler(NoExceptionHandler.class)
        @ExceptionHandler(AnticipatedExceptionHandler2.class)
        public HttpResponse asyncHttpResponse(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(completeExceptionallyLater(ctx));
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
            return HttpResponse.of(completeExceptionallyLater(ctx));
        }

        @Get("/bad2")
        @ExceptionHandler(BadExceptionHandler2.class)
        public HttpResponse bad2(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(completeExceptionallyLater(ctx));
        }

        @Get("/bad3")
        @ExceptionHandler(BadExceptionHandler3.class)
        public HttpResponse bad3(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(completeExceptionallyLater(ctx));
        }
    }

    @ExceptionHandler(AnticipatedExceptionHandler3.class)
    public static class MyService4 extends MyService1 {
        @Get("/handler3")
        public HttpResponse handler3(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.of(completeExceptionallyLater(ctx));
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

    @LoggingDecorator
    @ExceptionHandler(CapturingExceptionHandler.class)
    public static class MyService6 {
        @Post("/simple")
        @RequestConverter(ThrowingRequestConverterFunction.class)
        public HttpResponse post1(SimpleRequest simpleRequest) {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/composite")
        public HttpResponse post2(CompositeRequest compositeRequest) {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/throwing-setter")
        public HttpResponse post3(ThrowingSetter throwingSetter) {
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

    static class CapturingExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            capturedException.set(cause);
            return HttpResponse.of(200);
        }
    }

    static class ThrowingRequestConverterFunction implements RequestConverterFunction {

        @Override
        public @Nullable Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                               Class<?> expectedResultType,
                                               @Nullable ParameterizedType expectedParameterizedResultType)
                throws Exception {
            throw EXCEPTION;
        }
    }

    static class ThrowingSetter {

        @RequestConverter(ThrowingRequestConverterFunction.class)
        public void setField(String field) {
            throw EXCEPTION;
        }
    }

    static class CompositeRequest {
        @RequestConverter(ThrowingRequestConverterFunction.class)
        SimpleRequest simpleRequest;

        public static class SimpleRequest {
            String hello;
        }
    }
}
