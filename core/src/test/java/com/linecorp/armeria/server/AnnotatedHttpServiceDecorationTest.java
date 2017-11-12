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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.AnnotatedHttpServiceTest.validateContextAndRequest;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverter;
import com.linecorp.armeria.server.annotation.Converter;
import com.linecorp.armeria.server.annotation.Decorate;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

public class AnnotatedHttpServiceDecorationTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/1", new MyDecorationService1(),
                                LoggingService.newDecorator());

            sb.annotatedService("/2", new MyDecorationService2(),
                                LoggingService.newDecorator());
        }
    };

    @Rule
    public TestWatcher watchman = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            rule.server().config().virtualHosts().forEach(vh -> vh.router().dump(System.err));
        }
    };

    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyDecorationService1 {

        @Get("/tooManyRequests")
        @Decorate(AlwaysTooManyRequestsDecorator.class)
        public String tooManyRequests(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }

        @Get("/locked")
        @Decorate({ FallThroughDecorator.class, AlwaysLockedDecorator.class })
        public String locked(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }

        @Get("/ok")
        @Decorate(FallThroughDecorator.class)
        public String ok(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }
    }

    @Converter(target = String.class, value = UnformattedStringConverter.class)
    public static class MyDecorationService2 extends MyDecorationService1 {

        @Override
        @Get("/override")
        @Decorate(AlwaysTooManyRequestsDecorator.class)
        public String ok(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }

        @Get("/added")
        @Decorate(FallThroughDecorator.class)
        public String added(ServiceRequestContext ctx, HttpRequest req) {
            validateContextAndRequest(ctx, req);
            return "OK";
        }
    }

    public static final class AlwaysTooManyRequestsDecorator
            implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

        public AlwaysTooManyRequestsDecorator() {}

        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            validateContextAndRequest(ctx, req);
            throw new HttpResponseException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    static final class AlwaysLockedDecorator
            implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

        AlwaysLockedDecorator() {}

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

        private FallThroughDecorator() {}

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
        assertThat(response.headers().status(), is(HttpStatus.OK));

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/1/tooManyRequests")).aggregate().get();
        assertThat(response.headers().status(), is(HttpStatus.TOO_MANY_REQUESTS));

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/1/locked")).aggregate().get();
        assertThat(response.headers().status(), is(HttpStatus.LOCKED));

        // Call inherited methods.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/tooManyRequests")).aggregate().get();
        assertThat(response.headers().status(), is(HttpStatus.TOO_MANY_REQUESTS));

        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/locked")).aggregate().get();
        assertThat(response.headers().status(), is(HttpStatus.LOCKED));

        // Call a new method.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/added")).aggregate().get();
        assertThat(response.headers().status(), is(HttpStatus.OK));

        // Call an overriding method.
        response = client.execute(HttpHeaders.of(HttpMethod.GET, "/2/override")).aggregate().get();
        assertThat(response.headers().status(), is(HttpStatus.TOO_MANY_REQUESTS));
    }
}
