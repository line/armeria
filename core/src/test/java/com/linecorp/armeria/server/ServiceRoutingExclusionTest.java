/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class ServiceRoutingExclusionTest {
    private static AtomicBoolean decoratorCallChecker1;
    private static AtomicBoolean decoratorCallChecker2;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService myService = (ctx, req) -> HttpResponse.of(HttpStatus.NO_CONTENT);

            // Excluding a service route:
            // Matches /home/ and /home/ikhoon, but not /home/trustin
            sb.route().pathPrefix("/home")
              .exclude(Route.builder().pathPrefix("/home/trustin").build())
              .build(myService.decorate((delegate, ctx, req) -> {
                  decoratorCallChecker1.set(true);
                  return delegate.serve(ctx, req);
              }));

            // Excluding a decorator route:
            // Matches /home/ and /home/trustin, but not /home/ikhoon
            sb.routeDecorator().pathPrefix("/home")
              .exclude(Route.builder().pathPrefix("/home/ikhoon").build())
              .build((delegate, ctx, req) -> {
                  decoratorCallChecker2.set(true);
                  return delegate.serve(ctx, req);
              });
        }
    };

    @BeforeEach
    void setUp() {
        decoratorCallChecker1 = new AtomicBoolean();
        decoratorCallChecker2 = new AtomicBoolean();
    }

    @Test
    void noExclusion() {
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/home/foo").aggregate().join().status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(decoratorCallChecker1.get()).isTrue();
        assertThat(decoratorCallChecker2.get()).isTrue();
    }

    @Test
    void excludedServiceRoute() {
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/home/trustin/foo").aggregate().join().status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(decoratorCallChecker1.get()).isFalse();
        // A fallback service is decorated.
        assertThat(decoratorCallChecker2.get()).isTrue();
    }

    @Test
    void excludedDecoratorRoute() {
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/home/ikhoon/foo").aggregate().join().status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(decoratorCallChecker1.get()).isTrue();
        assertThat(decoratorCallChecker2.get()).isFalse();
    }
}
