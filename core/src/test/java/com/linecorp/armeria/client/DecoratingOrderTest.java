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

package com.linecorp.armeria.client;

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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecoratingOrderTest {

    static Queue<Integer> queue = new ArrayDeque<>();

    @BeforeEach
    void init() {
        queue = new ArrayDeque<>();
    }

    static Function<? super HttpClient, ? extends HttpClient> newDecorator(int id) {
        return delegate -> (ctx, req) -> {
            queue.add(id);
            return delegate.execute(ctx, req);
        };
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @ParameterizedTest
    @MethodSource("generateDecorateInOrder")
    void decorateInOrder(WebClient client, List<Integer> orders) {
        client.get("/").aggregate().join();
        assertThat(queue).containsExactlyElementsOf(orders);
    }

    static Stream<Arguments> generateDecorateInOrder() {
        return Stream.of(
                Arguments.of(WebClient.builder(server.httpUri())
                                      .decorator(newDecorator(1))
                                      .decorator(newDecorator(2))
                                      .decorator(newDecorator(3))
                                      .build(),
                             ImmutableList.of(3, 2, 1)),
                Arguments.of(WebClient.builder(server.httpUri())
                                      .decorator(newDecorator(1))
                                      .decorator(newDecorator(2), -1)
                                      .decorator(newDecorator(3), 1)
                                      .build(),
                             ImmutableList.of(2, 1, 3)),
                Arguments.of(Clients.builder(server.httpUri())
                                    .decorator(newDecorator(1))
                                    .decorator(newDecorator(2))
                                    .decorator(newDecorator(3))
                                    .build(WebClient.class),
                             ImmutableList.of(3, 2, 1)),
                Arguments.of(Clients.builder(server.httpUri())
                                    .decorator(newDecorator(1), -1)
                                    .decorator(newDecorator(2))
                                    .decorator(newDecorator(3), 1)
                                    .build(WebClient.class),
                             ImmutableList.of(1, 2, 3)));
    }
}
