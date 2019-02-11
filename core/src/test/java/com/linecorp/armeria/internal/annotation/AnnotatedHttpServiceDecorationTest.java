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

package com.linecorp.armeria.internal.annotation;

import static com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceTest.validateContextAndRequest;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpServiceDecorationTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyDecorationService1());
            sb.annotatedService("/2", new MyDecorationService2());
            sb.annotatedService("/3", new MyDecorationService3());
            sb.annotatedService("/4", new MyDecorationService4());
        }
    };

    @LoggingDecorator(requestLogLevel = LogLevel.INFO, successfulResponseLogLevel = LogLevel.INFO)
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyDecorationService1 {

        @Get("/tooManyRequests")
        @Decorator(AlwaysTooManyRequestsDecorator.class)
        public String tooManyRequests(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }

        @Get("/locked")
        @Decorator(FallThroughDecorator.class)
        @Decorator(AlwaysLockedDecorator.class)
        public String locked(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }

        @Get("/ok")
        @Decorator(FallThroughDecorator.class)
        public String ok(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }
    }

    @LoggingDecorator
    @ResponseConverter(UnformattedStringConverterFunction.class)
    public static class MyDecorationService2 extends MyDecorationService1 {

        @Override
        @Get("/override")
        @Decorator(AlwaysTooManyRequestsDecorator.class)
        public String ok(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }

        @Get("/added")
        @Decorator(FallThroughDecorator.class)
        public String added(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }
    }

    @LoggingDecorator
    @ResponseConverter(UnformattedStringConverterFunction.class)
    @Decorator(AlwaysTooManyRequestsDecorator.class)
    public static class MyDecorationService3 {

        @Get("/tooManyRequests")
        @Decorator(AlwaysLockedDecorator.class)
        public String tooManyRequests(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }
    }

    @LoggingDecorator
    @ResponseConverter(UnformattedStringConverterFunction.class)
    @Decorator(FallThroughDecorator.class)
    public static class MyDecorationService4 {

        @Get("/tooManyRequests")
        @Decorator(AlwaysTooManyRequestsDecorator.class)
        public String tooManyRequests(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }
    }

    public static final class AlwaysTooManyRequestsDecorator
            implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            validateContextAndRequest(ctx, req);
            throw HttpStatusException.of(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    static final class AlwaysLockedDecorator
            implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            validateContextAndRequest(ctx, req);
            return HttpResponse.of(HttpStatus.LOCKED);
        }
    }

    private static final class FallThroughDecorator
            implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            validateContextAndRequest(ctx, req);
            return delegate.serve(ctx, req);
        }
    }

    @Test
    public void testDecoratingAnnotatedService() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/"));

        AggregatedHttpMessage response;

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/1/ok")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/1/tooManyRequests")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/1/locked")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.LOCKED);

        // Call inherited methods.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/tooManyRequests")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/locked")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.LOCKED);

        // Call a new method.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/added")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Call an overriding method.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/override")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Respond by the class-level decorator.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/3/tooManyRequests")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Respond by the method-level decorator.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/4/tooManyRequests")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
