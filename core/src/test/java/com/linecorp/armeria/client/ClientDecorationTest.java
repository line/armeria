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
import java.util.Queue;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.internal.common.DecoratorAndOrder;

class ClientDecorationTest {

    static Queue<Integer> queue = new ArrayDeque<>();

    static Function<? super HttpClient, ? extends HttpClient> newDecorator(int id) {
        return delegate -> (ctx, req) -> {
            queue.add(id);
            return delegate.execute(ctx, req);
        };
    }

    static Function<? super RpcClient, ? extends RpcClient> newRpcDecorator(int id) {
        return delegate -> (ctx, req) -> {
            queue.add(id);
            return delegate.execute(ctx, req);
        };
    }

    @Test
    void decorateInOrder() throws Exception {
        final HttpClient client = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final RpcClient rpcClient = (ctx, req) -> RpcResponse.of("hello");
        final ClientDecoration decoration = new ClientDecoration(
                ImmutableList.<DecoratorAndOrder<HttpClient>>builder()
                        .add(new DecoratorAndOrder<>(newDecorator(1), 1))
                        .add(new DecoratorAndOrder<>(newDecorator(2), -1))
                        .add(new DecoratorAndOrder<>(newDecorator(3), DecoratorAndOrder.DEFAULT_ORDER))
                        .add(new DecoratorAndOrder<>(newDecorator(4), DecoratorAndOrder.DEFAULT_ORDER))
                        .add(new DecoratorAndOrder<>(newDecorator(5), 3))
                        .build(),
                ImmutableList.<DecoratorAndOrder<RpcClient>>builder()
                        .add(new DecoratorAndOrder<>(newRpcDecorator(1), 1))
                        .add(new DecoratorAndOrder<>(newRpcDecorator(2), -1))
                        .add(new DecoratorAndOrder<>(newRpcDecorator(3), 3))
                        .add(new DecoratorAndOrder<>(newRpcDecorator(4), DecoratorAndOrder.DEFAULT_ORDER))
                        .add(new DecoratorAndOrder<>(newRpcDecorator(5), DecoratorAndOrder.DEFAULT_ORDER))
                        .build());

        final HttpRequest httpRequest = HttpRequest.of(HttpMethod.GET, "/");
        final HttpResponse httpResponse = decoration.decorate(client)
                                                    .execute(ClientRequestContext.of(httpRequest), httpRequest);
        assertThat(queue).containsExactlyElementsOf(ImmutableList.of(2, 4, 3, 1, 5));

        queue.clear();

        final RpcRequest rpcRequest = RpcRequest.of(Object.class, "dummyMethod");
        final RpcResponse rpcResponse =
                decoration.rpcDecorate(rpcClient)
                          .execute(ClientRequestContext.of(rpcRequest, "h2c://dummyhost:8080/"), rpcRequest);
        assertThat(queue).containsExactlyElementsOf(ImmutableList.of(2, 5, 4, 1, 3));
    }
}
