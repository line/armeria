/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

class ClientDecorationTest {
    @Test
    void validateDecorator() {
        final HttpClient client = (ctx, req) -> HttpResponse.of(HttpStatus.OK);

        assertThatThrownBy(() -> ClientDecoration.validateDecorator(client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Client.as()");

        final HttpClient decorator = new SimpleDecoratingHttpClient(client) {
            @Override
            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                return HttpResponse.of(HttpStatus.OK);
            }
        };
        ClientDecoration.validateDecorator(decorator);
    }

    @Test
    void validateDecoratorFunction() {
        final Function<? super HttpClient, ? extends HttpClient> decoratorFunction =
                delegate -> new SimpleDecoratingHttpClient(delegate) {
                    @Override
                    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                        return delegate.execute(ctx, req);
                    }
                };
        final HttpClient client = (ctx, req) -> HttpResponse.of(HttpStatus.OK);

        ClientDecoration.of(decoratorFunction).decorate(client);

        assertThatThrownBy(() -> ClientDecoration.of(Function.identity()).decorate(client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Client.as()");
    }

    @Test
    void validateRpcDecorator() {
        final RpcClient client = (ctx, req) -> RpcResponse.of(null);

        assertThatThrownBy(() -> ClientDecoration.validateDecorator(client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Client.as()");

        final RpcClient decorator = new SimpleDecoratingRpcClient(client) {
            @Override
            public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
                return RpcResponse.of(null);
            }
        };
        ClientDecoration.validateDecorator(decorator);
    }

    @Test
    void validateRpcDecoratorFunction() {
        final Function<? super RpcClient, ? extends RpcClient> rpcDecoratorFunction =
                delegate -> new SimpleDecoratingRpcClient(delegate) {
                    @Override
                    public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
                        return delegate.execute(ctx, req);
                    }
                };
        final RpcClient client = (ctx, req) -> RpcResponse.of(null);

        ClientDecoration.ofRpc(rpcDecoratorFunction).rpcDecorate(client);

        assertThatThrownBy(() -> ClientDecoration.ofRpc(Function.identity()).rpcDecorate(client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Client.as()");
    }
}
