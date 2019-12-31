/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.composition;

import java.util.List;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.Routed;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.RpcService;

/**
 * A general purpose {@link AbstractCompositeService} implementation. Useful when you do not want to define
 * a new dedicated {@link RpcService} type.
 */
public final class SimpleCompositeRpcService
        extends AbstractCompositeService<RpcService, RpcRequest, RpcResponse> implements RpcService {

    /**
     * Returns a new {@link SimpleCompositeRpcServiceBuilder}.
     */
    public static SimpleCompositeRpcServiceBuilder builder() {
        return new SimpleCompositeRpcServiceBuilder();
    }

    /**
     * Creates a new instance that is composed of the specified entries.
     */
    @SafeVarargs
    public SimpleCompositeRpcService(CompositeServiceEntry<RpcService>... services) {
        super(services);
    }

    /**
     * Creates a new instance that is composed of the specified entries.
     */
    public SimpleCompositeRpcService(Iterable<CompositeServiceEntry<RpcService>> services) {
        super(services);
    }

    @Override
    public List<CompositeServiceEntry<RpcService>> services() {
        return super.services();
    }

    @Override
    public RpcService serviceAt(int index) {
        return super.serviceAt(index);
    }

    @Override
    public Routed<RpcService> findService(RoutingContext routingCtx) {
        return super.findService(routingCtx);
    }
}
