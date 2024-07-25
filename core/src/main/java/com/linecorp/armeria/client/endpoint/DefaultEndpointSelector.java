/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import java.util.List;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;

final class DefaultEndpointSelector<T extends LoadBalancer<Endpoint, ClientRequestContext>>
        extends AbstractEndpointSelector {

    private final LoadBalancerFactory<T> loadBalancerFactory;
    @Nullable
    private volatile T loadBalancer;

    DefaultEndpointSelector(EndpointGroup endpointGroup,
                            LoadBalancerFactory<T> loadBalancerFactory) {
        super(endpointGroup);
        this.loadBalancerFactory = loadBalancerFactory;
        if (endpointGroup instanceof ListenableAsyncCloseable) {
            ((ListenableAsyncCloseable) endpointGroup).whenClosed().thenAccept(unused -> {
                final T loadBalancer = this.loadBalancer;
                if (loadBalancer != null) {
                    loadBalancer.close();
                }
            });
        }
        initialize();
    }

    @Override
    protected void updateNewEndpoints(List<Endpoint> endpoints) {
        loadBalancer = loadBalancerFactory.newLoadBalancer(loadBalancer, endpoints);
    }

    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        final T loadBalancer = this.loadBalancer;
        if (loadBalancer == null) {
            return null;
        }
        return loadBalancer.pick(ctx);
    }

    @FunctionalInterface
    interface LoadBalancerFactory<T extends LoadBalancer<Endpoint, ClientRequestContext>> {
        T newLoadBalancer(@Nullable T oldLoadBalancer, List<Endpoint> candidates);
    }
}
