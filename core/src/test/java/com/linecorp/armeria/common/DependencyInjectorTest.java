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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DependencyInjectorTest {

    private static final AtomicInteger requestCounter = new AtomicInteger();
    private static final AtomicInteger responseCounter = new AtomicInteger();
    private static final AtomicInteger exceptionCounter = new AtomicInteger();
    private static final AtomicInteger decoratorCounter = new AtomicInteger();
    private static final AtomicInteger factoryCounter = new AtomicInteger();
    private static final AtomicInteger closeCounter = new AtomicInteger();

    private static final List<FooRequestConverter> converters = new BlockingArrayQueue<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final DependencyInjector dependencyInjector =
                    DependencyInjector.ofSingletons(new FooRequestConverter(requestCounter),
                                                    new FooResponseConverter(responseCounter),
                                                    new FooExceptionHandler(exceptionCounter),
                                                    new FooDecoratorFactory(factoryCounter),
                                                    new FooDecorator(decoratorCounter));
            sb.annotatedService(new Foo())
              .dependencyInjector(dependencyInjector, true);
        }
    };

    static class Foo {

        @Get("/foo")
        @FooDecoratorAnnotation
        @Decorator(FooDecorator.class)
        @ResponseConverter(FooResponseConverter.class)
        @RequestConverter(FooRequestConverter.class)
        public FooResponse foo(FooRequest req) {
            return new FooResponse();
        }

        @Get("/fooException")
        @Decorator(FooDecorator.class)
        @ExceptionHandler(FooExceptionHandler.class)
        @RequestConverter(FooRequestConverter.class)
        public FooResponse fooException(FooRequest req) {
            throw new AnticipatedException();
        }
    }

    static class FooRequest {}

    static class FooResponse {}

    @DecoratorFactory(FooDecoratorFactory.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface FooDecoratorAnnotation {}

    @Test
    void dependencyInjector() {
        // The factory counter is incremented only once when the instance is created.
        assertThat(factoryCounter.get()).isOne();

        assertThat(server.webClient().get("/foo").aggregate().join().status()).isSameAs(HttpStatus.OK);
        assertThat(requestCounter.get()).isOne();
        assertThat(responseCounter.get()).isOne();
        assertThat(decoratorCounter.get()).isOne();

        assertThat(server.webClient().get("/fooException").aggregate().join().status()).isSameAs(HttpStatus.OK);
        assertThat(requestCounter.get()).isSameAs(2);
        assertThat(exceptionCounter.get()).isOne();
        assertThat(decoratorCounter.get()).isSameAs(2);

        assertThat(converters.size()).isSameAs(2);
        // singletons
        assertThat(converters.get(0)).isSameAs(converters.get(1));

        // The factory counter is incremented only once when the instance is created.
        assertThat(factoryCounter.get()).isOne();
    }

    static class FooRequestConverter implements RequestConverterFunction {

        private final AtomicInteger counter;

        FooRequestConverter(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public @Nullable Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                               Class<?> expectedResultType,
                                               @Nullable ParameterizedType expectedParameterizedResultType)
                throws Exception {
            counter.incrementAndGet();
            converters.add(this);
            if (expectedResultType == FooRequest.class) {
                return new FooRequest();
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    static class FooResponseConverter implements ResponseConverterFunction {

        private final AtomicInteger counter;

        FooResponseConverter(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            counter.incrementAndGet();
            return HttpResponse.of(200);
        }
    }

    private static class FooExceptionHandler implements ExceptionHandlerFunction {

        private final AtomicInteger counter;

        FooExceptionHandler(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            counter.incrementAndGet();
            return HttpResponse.of(200);
        }
    }

    private static class FooDecorator implements DecoratingHttpServiceFunction {

        private final AtomicInteger counter;

        FooDecorator(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            counter.incrementAndGet();
            return delegate.serve(ctx, req);
        }
    }

    private static class FooDecoratorFactory implements DecoratorFactoryFunction<FooDecoratorAnnotation> {

        private final AtomicInteger counter;

        FooDecoratorFactory(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public Function<? super HttpService, ? extends HttpService> newDecorator(
                FooDecoratorAnnotation parameter) {
            counter.incrementAndGet();
            return service -> service;
        }
    }

    @Test
    void instanceIsClosed() {
        final Server server = Server.builder().annotatedService(new Object() {
            @Decorator(CloseableDecorator.class)
            @Get("/foo")
            public HttpResponse foo() {
                return HttpResponse.of(200);
            }
        }).build();
        server.start().join();
        assertThat(closeCounter.get()).isZero();
        server.stop().join();
        assertThat(closeCounter.get()).isOne();
    }

    private static class CloseableDecorator implements DecoratingHttpServiceFunction, AutoCloseable {

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return delegate.serve(ctx, req);
        }

        @Override
        public void close() throws Exception {
            closeCounter.incrementAndGet();
        }
    }
}
