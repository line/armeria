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

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecoratingOrderTest {

    static Queue<Integer> queue = new ArrayDeque<>();

    @BeforeEach
    void init() {
        queue = new ArrayDeque<>();
    }

    static Function<? super HttpService, ? extends HttpService> newDecorator(int id) {
        return delegate -> (ctx, req) -> {
            queue.add(id);
            return delegate.serve(ctx, req);
        };
    }

    static class AnnotatedServiceDecoratingHttpServiceFunction implements DecoratingHttpServiceFunction {

        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            queue.add(1);
            return delegate.serve(ctx, req);
        }
    }

    @Decorator(value = AnnotatedServiceDecoratingHttpServiceFunction.class, order = Integer.MIN_VALUE)
    static class TestService {
        @Get
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    @RegisterExtension
    static ServerExtension decoratingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb
              .annotatedService()
                  .pathPrefix("/a")
                  .decorator(newDecorator(1))
                  .decorator(newDecorator(2))
                  .decorator(newDecorator(3))
                  .build(new TestService())
              .decorator("/a", newDecorator(4))
              .decorator("/a", newDecorator(5))
              .decorator("/a", newDecorator(6))
              .annotatedService()
                  .pathPrefix("/b")
                  .decorator(newDecorator(1))
                  .decorator(newDecorator(2), -1)
                  .decorator(newDecorator(3), 1)
                  .build(new TestService())
              .decorator("/b", newDecorator(4))
              .decorator("/b", newDecorator(5), -2)
              .decorator("/b", newDecorator(6), 2)
              .route()
                  .path("/c")
                  .decorator(newDecorator(1))
                  .decorator(newDecorator(2))
                  .decorator(newDecorator(3))
                  .build((ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator("/c", newDecorator(4))
              .decorator("/c", newDecorator(5))
              .decorator("/c", newDecorator(6))
              .route()
                  .path("/d")
                  .decorator(newDecorator(1), 1)
                  .decorator(newDecorator(2), -1)
                  .decorator(newDecorator(3))
                  .build((ctx, req) -> HttpResponse.of(HttpStatus.OK))
              .decorator("/d", newDecorator(4), 2)
              .decorator("/d", newDecorator(5), -2)
              .decorator("/d", newDecorator(6));
        }
    };

    @ParameterizedTest
    @MethodSource("generateDecorateInOrder")
    void decorateInOrder(String path, List<Integer> orders) {
        final WebClient client = WebClient.of(decoratingServer.httpUri());
        client.get(path).aggregate().join();
        assertThat(queue).containsExactlyElementsOf(orders);
    }

    static Stream<Arguments> generateDecorateInOrder() {
        return Stream.of(
                Arguments.of("/a", ImmutableList.of(6, 5, 4, 3, 2, 1, 1)),
                Arguments.of("/b", ImmutableList.of(5, 4, 6, 2, 1, 3, 1)),
                Arguments.of("/c", ImmutableList.of(6, 5, 4, 3, 2, 1)),
                Arguments.of("/d", ImmutableList.of(5, 6, 4, 2, 3, 1)));
    }
}
