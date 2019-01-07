/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpServiceHandlersOrderTest {

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyDecorationService1(), LoggingService.newDecorator(),
                                new ServiceLevelRequestConverter(), new ServiceLevelResponseConverter(),
                                new ServiceLevelExceptionHandler());
        }
    };

    private static final AtomicInteger requestCounter = new AtomicInteger();

    private static final AtomicInteger responseCounter = new AtomicInteger();

    private static final AtomicInteger exceptionCounter = new AtomicInteger();

    @RequestConverter(ClassLevelRequestConverter.class)
    @ResponseConverter(ClassLevelResponseConverter.class)
    @ExceptionHandler(ClassLevelExceptionHandler.class)
    private static class MyDecorationService1 {

        @Post("/requestConverterOrder")
        @RequestConverter(MethodLevelRequestConverter.class)
        public HttpResponse requestConverterOrder(
                @RequestConverter(ParameterLevelRequestConverter.class) JsonNode node) {
            assertThat(node).isNotNull();
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.ofUtf8(node.toString()));
        }

        @Post("/responseConverterOrder")
        @ResponseConverter(MethodLevelResponseConverter.class)
        public String responseConverterOrder(@RequestObject String name) {
            assertThat(name).isEqualTo("foo");
            return "hello " + name;
        }

        @Post("/exceptionHandlerOrder")
        @ExceptionHandler(MethodLevelExceptionHandler.class)
        public HttpResponse exceptionHandlerOrder(@RequestObject String name) {
            assertThat(name).isEqualTo("foo");
            final AggregatedHttpMessage message = AggregatedHttpMessage.of(
                    HttpStatus.NOT_IMPLEMENTED, MediaType.PLAIN_TEXT_UTF_8, "hello " + name);
            throw HttpResponseException.of(message);
        }
    }

    // RequestConverterFunction starts

    private static class ParameterLevelRequestConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) throws Exception {
            if (expectedResultType == JsonNode.class) {
                assertThat(requestCounter.getAndIncrement()).isZero();
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    private static class MethodLevelRequestConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) throws Exception {
            if (expectedResultType == JsonNode.class) {
                assertThat(requestCounter.getAndIncrement()).isOne();
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    private static class ClassLevelRequestConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) throws Exception {
            if (expectedResultType == JsonNode.class) {
                assertThat(requestCounter.getAndIncrement()).isEqualTo(2);
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    private static class ServiceLevelRequestConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) throws Exception {
            if (expectedResultType == JsonNode.class) {
                assertThat(requestCounter.getAndIncrement()).isEqualTo(3);
            }
            return RequestConverterFunction.fallthrough();
        }
    }

    // RequestConverterFunction ends

    // ResponseConverterFunction starts

    private static class MethodLevelResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            HttpHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailingHeaders) throws Exception {
            if (result instanceof String && "hello foo".equals(result)) {
                assertThat(responseCounter.getAndIncrement()).isZero();
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    private static class ClassLevelResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            HttpHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailingHeaders) throws Exception {
            if (result instanceof String && "hello foo".equals(result)) {
                assertThat(responseCounter.getAndIncrement()).isOne();
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    private static class ServiceLevelResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            HttpHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailingHeaders) throws Exception {
            if (result instanceof String && "hello foo".equals(result)) {
                assertThat(responseCounter.getAndIncrement()).isEqualTo(2);
                return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.ofUtf8(
                        (String) result));
            }
            return ResponseConverterFunction.fallthrough();
        }
    }

    // ResponseConverterFunction ends

    // ExceptionHandlerFunction starts

    private static class MethodLevelExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            assertThat(exceptionCounter.getAndIncrement()).isZero();
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    private static class ClassLevelExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            assertThat(exceptionCounter.getAndIncrement()).isOne();
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    private static class ServiceLevelExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            assertThat(exceptionCounter.getAndIncrement()).isEqualTo(2);
            return ExceptionHandlerFunction.fallthrough();
        }
    }

    // ExceptionHandlerFunction ends

    @Test
    public void requestConverterOrder() throws Exception {
        final String body = "{\"foo\":\"bar\"}";
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.POST, "/1/requestConverterOrder", MediaType.JSON, body);

        final AggregatedHttpMessage aRes = executeRequest(aReq);

        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
        // Converted from the default converter which is JacksonRequestConverterFunction.
        assertThat(aRes.content().toStringUtf8()).isEqualTo(body);

        // parameter level(+1) -> method level(+1) -> class level(+1) -> service level(+1) -> default
        assertThat(requestCounter.get()).isEqualTo(4);
    }

    @Test
    public void responseConverterOrder() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.POST, "/1/responseConverterOrder", MediaType.PLAIN_TEXT_UTF_8, "foo");
        final AggregatedHttpMessage aRes = executeRequest(aReq);

        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
        // Converted from the ServiceLevelResponseConverter.
        assertThat(aRes.content().toStringUtf8()).isEqualTo("hello foo");

        // method level(+1) -> class level(+1) -> service level(+1)
        assertThat(responseCounter.get()).isEqualTo(3);
    }

    @Test
    public void exceptionHandlerOrder() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.POST, "/1/exceptionHandlerOrder", MediaType.PLAIN_TEXT_UTF_8, "foo");
        final AggregatedHttpMessage aRes = executeRequest(aReq);

        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        // Converted from the default Handler which is DefaultExceptionHandler in AnnotatedHttpServices.
        assertThat(aRes.content().toStringUtf8()).isEqualTo("hello foo");

        // method level(+1) -> class level(+1) -> service level(+1) -> default
        assertThat(exceptionCounter.get()).isEqualTo(3);
    }

    private static AggregatedHttpMessage executeRequest(AggregatedHttpMessage req) {
        final HttpClient client = HttpClient.of(server.uri("/"));
        return client.execute(req).aggregate().join();
    }
}
