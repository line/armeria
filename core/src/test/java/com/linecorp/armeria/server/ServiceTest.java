/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

public class ServiceTest {

    /**
     * Tests if a user can write a decorator with working as() and serviceAdded() using lambda expressions only.
     */
    @Test
    public void testLambdaExpressionDecorator() throws Exception {
        final FooService inner = new FooService();
        final Service<RpcRequest, RpcResponse> outer = inner.decorate((delegate, ctx, req) -> {
            RpcRequest newReq = RpcRequest.of(req.serviceType(), "new_" + req.method(), req.params());
            return delegate.serve(ctx, newReq);
        });

        // Test if Service.as() works as expected.
        assertThat(outer.as(serviceType(inner))).containsSame(inner);
        assertThat(outer.as(serviceType(outer))).containsSame(outer);
        assertThat(outer.as(String.class)).isNotPresent();

        // Test if FooService.serviceAdded() is invoked.
        final ServiceConfig cfg = new ServiceConfig(PathMapping.ofCatchAll(), outer, "foo");
        outer.serviceAdded(cfg);
        assertThat(inner.cfg).isSameAs(cfg);
    }

    @SuppressWarnings("unchecked")
    private static Class<Service<?, ?>> serviceType(Service<?, ?> service) {
        return (Class<Service<?, ?>>) service.getClass();
    }

    private static final class FooService implements Service<RpcRequest, RpcResponse> {

        ServiceConfig cfg;

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            this.cfg = cfg;
        }

        @Override
        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
            // Will never reach here.
            throw new Error();
        }
    }
}
