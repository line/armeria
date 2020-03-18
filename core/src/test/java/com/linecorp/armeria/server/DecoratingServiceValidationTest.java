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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.internal.server.DecoratingServiceUtil;

class DecoratingServiceValidationTest {

    private static final HttpService service = (ctx, req) -> HttpResponse.of(HttpStatus.OK);

    private static final Function<? super HttpService, ? extends HttpService> decoratorFunction =
            delegate -> new SimpleDecoratingHttpService(delegate) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return delegate.serve(ctx, req);
                }
            };

    @Test
    void invalidService_as() {
        final HttpService service = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        assertThatThrownBy(() -> DecoratingServiceUtil.validateDecorator(service))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Service.as()");
    }

    @Test
    void invalidService_serviceAdded() {
        final HttpService service = new UnwrappableService();
        assertThatThrownBy(() -> DecoratingServiceUtil.validateDecorator(service))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Service.serviceAdded()");
    }

    @Test
    void validateDecorator() {
        final HttpService service = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final HttpService decorator = new SimpleDecoratingHttpService(service) {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                return delegate().serve(ctx, req);
            }
        };
        DecoratingServiceUtil.validateDecorator(decorator);
    }

    @Test
    void validateRpcDecorator() {
        final RpcService service = (ctx, req) -> RpcResponse.of(null);
        final RpcService decorator = new SimpleDecoratingRpcService(service) {
            @Override
            public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
                return delegate().serve(ctx, req);
            }
        };
        DecoratingServiceUtil.validateDecorator(decorator);
    }

    @Test
    void validateDecoratorByServerBuilder() {
        Server.builder()
              .service("/", service)
              .decorator(decoratorFunction);

        assertThatThrownBy(() -> {
            Server.builder()
                  .service("/", service)
                  .decorator(Function.identity());
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("decorator should override Service.as()");
    }

    @Test
    void validateDecoratorByService() {
        service.decorate(decoratorFunction);

        assertThatThrownBy(() -> service.decorate(Function.identity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Service.as()");
    }

    private static final class UnwrappableService implements HttpService {
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
