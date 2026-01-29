/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancerFactory;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A pool of {@link XdsLoadBalancerFactory} which defers closing factories.
 * This may be useful when users wish to propagate endpoint-specific properties
 * across xDS resource reloads. (e.g., preserve health/ramping up even if a route is updated)
 */
final class LoadBalancerFactoryPool implements SafeCloseable {
    private final Map<String, XdsLoadBalancerFactory> factories = new HashMap<>();
    private final Map<String, DelayedClose> delayedCloses = new HashMap<>();

    private final MeterIdPrefix meterIdPrefix;
    private final MeterRegistry meterRegistry;
    private final EventExecutor eventLoop;
    private final Bootstrap bootstrap;

    LoadBalancerFactoryPool(MeterIdPrefix meterIdPrefix, MeterRegistry meterRegistry,
                            EventExecutor eventLoop, Bootstrap bootstrap) {
        this.meterIdPrefix = meterIdPrefix;
        this.meterRegistry = meterRegistry;
        this.eventLoop = eventLoop;
        this.bootstrap = bootstrap;
    }

    XdsLoadBalancerFactory register(String name) {
        maybeRemoveDelayedClose(name);
        final XdsLoadBalancerFactory cached = factories.get(name);
        if (cached != null) {
            return cached;
        }
        final DefaultXdsLoadBalancerLifecycleObserver observer =
                new DefaultXdsLoadBalancerLifecycleObserver(meterIdPrefix, meterRegistry, name);
        final XdsLoadBalancerFactory loadBalancer =
                XdsLoadBalancerFactory.of(eventLoop, bootstrap.getNode().getLocality(),
                                          observer);
        factories.put(name, loadBalancer);
        return loadBalancer;
    }

    void unregister(String name) {
        maybeRemoveDelayedClose(name);
        delayedCloses.put(name, new DelayedClose(name));
    }

    private void maybeRemoveDelayedClose(String name) {
        final DelayedClose delayedClose = delayedCloses.remove(name);
        if (delayedClose != null) {
            delayedClose.closeFuture.cancel(false);
        }
    }

    @Override
    public void close() {
        for (XdsLoadBalancerFactory loadBalancer : factories.values()) {
            loadBalancer.close();
        }
        for (DelayedClose delayedClose : delayedCloses.values()) {
            delayedClose.closeFuture.cancel(true);
        }
    }

    private class DelayedClose {

        private final ScheduledFuture<?> closeFuture;

        DelayedClose(String name) {
            closeFuture = eventLoop.schedule(() -> {
                final XdsLoadBalancerFactory loadBalancer = factories.remove(name);
                if (loadBalancer != null) {
                    loadBalancer.close();
                }
                delayedCloses.remove(name);
            }, 10, TimeUnit.SECONDS);
        }
    }
}
