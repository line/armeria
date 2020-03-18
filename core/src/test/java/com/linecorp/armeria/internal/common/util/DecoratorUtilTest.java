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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.SimpleDecoratingRpcClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;

class DecoratorUtilTest {

    @Test
    void invalidService_as() {
        final HttpService service = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        assertThatThrownBy(() -> DecoratorUtil.validateServiceDecorator(service))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Service.as()");
    }

    @Test
    void invalidService_serviceAdded() {
        final HttpService service = new UnwrappableService();
        assertThatThrownBy(() -> DecoratorUtil.validateServiceDecorator(service))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Service.serviceAdded()");
    }

    @Test
    void validHttpServiceDecorator() {
        final HttpService service = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final HttpService decorator = new SimpleDecoratingHttpService(service) {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                return delegate().serve(ctx, req);
            }
        };
        DecoratorUtil.validateServiceDecorator(decorator);
    }

    @Test
    void validRpcServiceDecorator() {
        final RpcService service = (ctx, req) -> RpcResponse.of(null);
        final RpcService decorator = new SimpleDecoratingRpcService(service) {
            @Override
            public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
                return delegate().serve(ctx, req);
            }
        };
        DecoratorUtil.validateServiceDecorator(decorator);
    }

    @Test
    void invalidClient_as() {
        final HttpClient client = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        assertThatThrownBy(() -> DecoratorUtil.validateClientDecorator(client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Client.as()");
    }

    @Test
    void validHttpClientDecorator() {
        final HttpClient client = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final HttpClient decorator = new SimpleDecoratingHttpClient(client) {
            @Override
            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                return HttpResponse.of(HttpStatus.OK);
            }
        };
        DecoratorUtil.validateClientDecorator(decorator);
    }

    @Test
    void validRpcClientDecorator() {
        final RpcClient client = (ctx, req) -> RpcResponse.of(null);
        final RpcClient decorator = new SimpleDecoratingRpcClient(client) {
            @Override
            public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
                return RpcResponse.of(null);
            }
        };
        DecoratorUtil.validateClientDecorator(decorator);
    }

    private static class UnwrappableService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Override
        public <T> T as(Class<T> type) {
            return type.cast(this);
        }
    }
}
