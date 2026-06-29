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

package com.linecorp.armeria.xds.server;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ConnectionContext;
import com.linecorp.armeria.server.ServiceCallbackInvoker;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.xds.FilterChainSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

@UnstableApi
final class ServerSnapshotWatcher implements SnapshotWatcher<ListenerSnapshot> {

    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    @Nullable
    private volatile ListenerSnapshot listenerSnapshot;
    @Nullable
    private volatile ServiceConfig serviceConfig;

    CompletableFuture<Void> whenReady() {
        return readyFuture;
    }

    void serviceAdded(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        invokeServiceAdded(serviceConfig);
    }

    private void invokeServiceAdded(ServiceConfig serviceConfig) {
        final ListenerSnapshot current = listenerSnapshot;
        if (current == null) {
            return;
        }
        for (FilterChainSnapshot chain : current.filterChains()) {
            invokeServiceAddedForChain(serviceConfig, chain);
        }
        final FilterChainSnapshot defaultChain = current.defaultFilterChain();
        if (defaultChain != null) {
            invokeServiceAddedForChain(serviceConfig, defaultChain);
        }
    }

    private static void invokeServiceAddedForChain(ServiceConfig serviceConfig,
                                                   FilterChainSnapshot chain) {
        final RouteSnapshot routeSnapshot = chain.routeSnapshot();
        if (routeSnapshot == null) {
            return;
        }
        for (VirtualHostSnapshot vh : routeSnapshot.virtualHostSnapshots()) {
            for (RouteEntry entry : vh.routeEntries()) {
                ServiceCallbackInvoker.invokeServiceAdded(serviceConfig, entry.httpService());
            }
        }
    }

    @Nullable
    FilterChainSnapshot match(ConnectionContext ctx) {
        final ListenerSnapshot current = listenerSnapshot;
        assert current != null;
        return current.matchFilterChain(ctx);
    }

    @Override
    public void onUpdate(@Nullable ListenerSnapshot listenerSnapshot, @Nullable Throwable t) {
        if (t != null) {
            readyFuture.completeExceptionally(t);
            return;
        }
        assert listenerSnapshot != null;

        final ServiceConfig cfg = serviceConfig;
        if (cfg != null) {
            invokeServiceAdded(cfg);
        }
        this.listenerSnapshot = listenerSnapshot;
        readyFuture.complete(null);
    }
}
