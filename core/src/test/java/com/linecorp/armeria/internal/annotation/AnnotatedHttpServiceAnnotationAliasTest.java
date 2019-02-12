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
package com.linecorp.armeria.internal.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.AdditionalTrailer;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public class AnnotatedHttpServiceAnnotationAliasTest {

    @RequestConverter(MyRequestConverter.class)
    @ResponseConverter(MyResponseConverter.class)
    @Consumes("text/plain; charset=utf-8")
    @Consumes("application/xml")
    @ConsumesJson
    @Produces("text/plain; charset=utf-8")
    @Produces("application/xml")
    @ProducesJson
    @ExceptionHandler(MyExceptionHandler1.class)
    @ExceptionHandler(MyExceptionHandler2.class)
    @LoggingDecorator(requestLogLevel = LogLevel.DEBUG, successfulResponseLogLevel = LogLevel.DEBUG)
    @Decorator(MyDecorator1.class)
    @Decorator(MyDecorator2.class)
    @MyDecorator3
    @Order  // Just checking whether @Order annotation can be present as a meta-annotation.
    @StatusCode(201)
    @AdditionalHeader(name = "x-foo", value = "foo")
    @AdditionalHeader(name = "x-bar", value = "bar")
    @AdditionalTrailer(name = "x-baz", value = "baz")
    @AdditionalTrailer(name = "x-qux", value = "qux")
    @Retention(RetentionPolicy.RUNTIME)
    @interface MyPostServiceSpecifications {}

    @RequestConverter(MyRequestConverter.class)
    @ResponseConverter(MyResponseConverter.class)
    @ProducesJson
    @ExceptionHandler(MyExceptionHandler1.class)
    @ExceptionHandler(MyExceptionHandler2.class)
    @LoggingDecorator(requestLogLevel = LogLevel.DEBUG, successfulResponseLogLevel = LogLevel.DEBUG)
    @Decorator(MyDecorator1.class)
    @Decorator(MyDecorator2.class)
    @MyDecorator3
    @AdditionalHeader(name = "x-foo", value = "foo")
    @AdditionalTrailer(name = "x-bar", value = "bar")
    @Retention(RetentionPolicy.RUNTIME)
    @interface MyGetServiceSpecifications {}

    static class MyRequest {
        private final String name;

        MyRequest(String name) {
            this.name = name;
        }
    }

    static class MyRequestConverter implements RequestConverterFunction {
        @Nullable
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) throws Exception {
            if (expectedResultType == MyRequest.class) {
                final String decorated = ctx.attr(decoratedFlag).get();
                return new MyRequest(request.contentUtf8() + decorated);
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    static class MyResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(
                ServiceRequestContext ctx, HttpHeaders headers,
                @Nullable Object result, HttpHeaders trailingHeaders) throws Exception {
            return HttpResponse.of(headers, HttpData.ofUtf8("Hello, %s!", result), trailingHeaders);
        }
    }

    static class MyExceptionHandler1 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof IllegalArgumentException) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                       "Cause:" + IllegalArgumentException.class.getSimpleName());
            }
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    static class MyExceptionHandler2 implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof IllegalStateException) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                       "Cause:" + IllegalStateException.class.getSimpleName());
            }
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    static class MyDecorator1 implements DecoratingServiceFunction<HttpRequest, HttpResponse> {
        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx, HttpRequest req) throws Exception {
            appendAttribute(ctx, " (decorated-1)");
            return delegate.serve(ctx, req);
        }
    }

    static class MyDecorator2 implements DecoratingServiceFunction<HttpRequest, HttpResponse> {
        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx, HttpRequest req) throws Exception {
            appendAttribute(ctx, " (decorated-2)");
            return delegate.serve(ctx, req);
        }
    }

    @DecoratorFactory(MyDecorator3Factory.class)
    @Retention(RetentionPolicy.RUNTIME)
    @interface MyDecorator3 {}

    static class MyDecorator3Factory implements DecoratorFactoryFunction<MyDecorator3> {
        @Override
        public Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> newDecorator(MyDecorator3 parameter) {
            return delegate -> new SimpleDecoratingService<HttpRequest, HttpResponse>(delegate) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    appendAttribute(ctx, " (decorated-3)");
                    return delegate().serve(ctx, req);
                }
            };
        }
    }

    static final AttributeKey<String> decoratedFlag =
            AttributeKey.valueOf(AnnotatedHttpServiceAnnotationAliasTest.class, "decorated");

    private static void appendAttribute(ServiceRequestContext ctx, String value) {
        final Attribute<String> attr = ctx.attr(decoratedFlag);
        final String v = attr.get();
        attr.set((v == null ? "" : v) + value);
    }

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Post("/hello")
                @MyPostServiceSpecifications
                public String hello(MyRequest myRequest) {
                    return myRequest.name;
                }

                @Get("/exception1")
                @MyGetServiceSpecifications
                public String exception1() {
                    throw new IllegalArgumentException("Anticipated!");
                }

                @Get("/exception2")
                @MyGetServiceSpecifications
                public String exception2() {
                    throw new IllegalStateException("Anticipated!");
                }
            });
        }
    };

    @Test
    public void metaAnnotations() {
        final AggregatedHttpMessage msg =
                HttpClient.of(rule.uri("/"))
                          .execute(HttpHeaders.of(HttpMethod.POST, "/hello")
                                              .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                              .add(HttpHeaderNames.ACCEPT, "text/*"),
                                   HttpData.ofUtf8("Armeria"))
                          .aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(msg.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(msg.headers().get(HttpHeaderNames.of("x-foo"))).isEqualTo("foo");
        assertThat(msg.headers().get(HttpHeaderNames.of("x-bar"))).isEqualTo("bar");
        assertThat(msg.contentUtf8())
                .isEqualTo("Hello, Armeria (decorated-1) (decorated-2) (decorated-3)!");
        assertThat(msg.trailingHeaders().get(HttpHeaderNames.of("x-baz"))).isEqualTo("baz");
        assertThat(msg.trailingHeaders().get(HttpHeaderNames.of("x-qux"))).isEqualTo("qux");
    }

    @Test
    public void metaOfMetaAnnotation_ProducesJson() {
        final AggregatedHttpMessage msg =
                HttpClient.of(rule.uri("/"))
                          .execute(HttpHeaders.of(HttpMethod.POST, "/hello")
                                              .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                              .add(HttpHeaderNames.ACCEPT,
                                                   "application/json; charset=utf-8"),
                                   HttpData.ofUtf8("Armeria"))
                          .aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(msg.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(msg.headers().get(HttpHeaderNames.of("x-foo"))).isEqualTo("foo");
        assertThat(msg.headers().get(HttpHeaderNames.of("x-bar"))).isEqualTo("bar");
        assertThat(msg.contentUtf8())
                .isEqualTo("Hello, Armeria (decorated-1) (decorated-2) (decorated-3)!");
        assertThat(msg.trailingHeaders().get(HttpHeaderNames.of("x-baz"))).isEqualTo("baz");
        assertThat(msg.trailingHeaders().get(HttpHeaderNames.of("x-qux"))).isEqualTo("qux");
    }

    @Test
    public void exception1() {
        final AggregatedHttpMessage msg =
                HttpClient.of(rule.uri("/")).get("/exception1").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // @AdditionalHeader/Trailer is added using ServiceRequestContext, so they are added even if
        // the request is not succeeded.
        assertThat(msg.headers().get(HttpHeaderNames.of("x-foo"))).isEqualTo("foo");
        assertThat(msg.contentUtf8())
                .isEqualTo("Cause:" + IllegalArgumentException.class.getSimpleName());
        assertThat(msg.trailingHeaders().get(HttpHeaderNames.of("x-bar"))).isEqualTo("bar");
    }

    @Test
    public void exception2() {
        final AggregatedHttpMessage msg =
                HttpClient.of(rule.uri("/")).get("/exception2").aggregate().join();
        assertThat(msg.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // @AdditionalHeader/Trailer is added using ServiceRequestContext, so they are added even if
        // the request is not succeeded.
        assertThat(msg.headers().get(HttpHeaderNames.of("x-foo"))).isEqualTo("foo");
        assertThat(msg.contentUtf8())
                .isEqualTo("Cause:" + IllegalStateException.class.getSimpleName());
        assertThat(msg.trailingHeaders().get(HttpHeaderNames.of("x-bar"))).isEqualTo("bar");
    }
}
