/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ServiceName;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceRequestLogNameTest {

    private static final BlockingQueue<RequestLogAccess> logs = new LinkedTransferQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new FooService());
            sb.annotatedService("/serviceName", new BarService());
            sb.annotatedService("/decorated", new BarService(), service -> {
                return service.decorate((delegate, ctx, req) -> {
                    ctx.logBuilder().name("DecoratedService", ctx.method().name());
                    return delegate.serve(ctx, req);
                });
            });

            sb.annotatedService()
              .pathPrefix("/configured")
              .defaultServiceName("ConfiguredService")
              .defaultLogName("ConfiguredLog")
              .build(new BarService());

            // The annotated service will not be invoked at all for '/fail_early'.
            sb.routeDecorator().path("/fail_early")
              .build((delegate, ctx, req) -> {
                  throw HttpStatusException.of(500);
              });

            sb.decorator((delegate, ctx, req) -> {
                logs.add(ctx.log());
                return delegate.serve(ctx, req);
            });
        }
    };

    @Nullable
    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void logNameShouldBeSet() throws Exception {
        client.get("/ok").aggregate().join();

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.name()).isEqualTo("foo");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logNameShouldBeSetOnEarlyFailure() throws Exception {
        client.get("/fail_early").aggregate().join();

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.name()).isEqualTo("bar");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void defaultServiceName() throws Exception {
        client.get("/ok").aggregate().join();

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo(FooService.class.getName());
    }

    @Test
    void customServiceNameWithClass() throws Exception {
        client.get("/serviceName/foo").aggregate().join();

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("MyBarService");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void customServiceNameWithMethod() throws Exception {
        final AggregatedHttpResponse response = client.get("/serviceName/bar").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("OK");

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("SecuredBarService");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void customServiceNameWithDecorator() throws Exception {
        final AggregatedHttpResponse response = client.get("/decorated/foo").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("OK");

        final RequestLog log = logs.take().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("DecoratedService");
        assertThat(log.name()).isEqualTo(HttpMethod.GET.name());
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void customServiceNameWithConfiguration() throws Exception {
        AggregatedHttpResponse response = client.get("/configured/foo").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("OK");

        RequestLog log = logs.take().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("ConfiguredService");
        assertThat(log.name()).isEqualTo("ConfiguredLog");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);

        response = client.get("/configured/bar").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("OK");

        log = logs.take().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("ConfiguredService");
        assertThat(log.name()).isEqualTo("ConfiguredLog");
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    private static class FooService {
        @Get("/ok")
        public String foo() {
            return "OK";
        }

        @Get("/fail_early")
        public String bar() {
            return "Not OK";
        }
    }

    @ServiceName("MyBarService")
    private static class BarService {
        @Get("/foo")
        public String foo() {
            return "OK";
        }

        @ServiceName("SecuredBarService")
        @Get("/bar")
        public String secured() {
            return "OK";
        }
    }
}
