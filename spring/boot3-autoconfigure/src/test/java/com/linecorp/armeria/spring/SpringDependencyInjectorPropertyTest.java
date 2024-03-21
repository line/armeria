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
package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.spring.SpringDependencyInjectorPropertyTest.TestConfiguration;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
class SpringDependencyInjectorPropertyTest {

    private static final AtomicInteger counter = new AtomicInteger();

    @SpringBootApplication
    public static class TestConfiguration {
        @Bean
        public ArmeriaServerConfigurator serverConfigurator() {
            return sb -> sb.annotatedService(new Foo());
        }

        @Bean
        public FooDecorator fooDecorator() {
            return new FooDecorator(counter);
        }

        public static final class Foo {

            @Decorator(FooDecorator.class)
            @Get("/foo")
            public HttpResponse foo() {
                return HttpResponse.of(200);
            }
        }

        public static final class FooDecorator implements DecoratingHttpServiceFunction {

            private final AtomicInteger reqCounter;

            FooDecorator(AtomicInteger reqCounter) {
                this.reqCounter = reqCounter;
            }

            @Override
            public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                    throws Exception {
                reqCounter.incrementAndGet();
                return delegate.serve(ctx, req);
            }
        }
    }

    @Inject
    private Server server;

    @Test
    void fooDecoratorInjectedViaDependencyInjector() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());

        final HttpResponse response = client.get("/foo");

        final AggregatedHttpResponse msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(counter.get()).isOne();
    }
}
