/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;

import io.envoyproxy.envoy.config.core.v3.Locality;

final class LocalCluster extends AbstractListenable<PrioritySet>
        implements AutoCloseable, Consumer<PrioritySet> {

    private final LocalityRoutingStateFactory localityRoutingStateFactory;
    private final UpdatableLoadBalancer localLoadBalancer;
    @Nullable
    private PrioritySet prioritySet;

    LocalCluster(Locality locality, XdsLoadBalancer localLoadBalancer) {
        localityRoutingStateFactory = new LocalityRoutingStateFactory(locality);
        assert localLoadBalancer instanceof UpdatableLoadBalancer;
        final UpdatableLoadBalancer updatableLoadBalancer = (UpdatableLoadBalancer) localLoadBalancer;
        this.localLoadBalancer = updatableLoadBalancer;
        updatableLoadBalancer.addListener(this, true);
    }

    @Override
    @Nullable
    protected PrioritySet latestValue() {
        return prioritySet;
    }

    LocalityRoutingStateFactory stateFactory() {
        return localityRoutingStateFactory;
    }

    @Override
    public void close() {
        localLoadBalancer.removeListener(this);
    }

    @Override
    public void accept(PrioritySet prioritySet) {
        this.prioritySet = prioritySet;
        notifyListeners(prioritySet);
    }
}
