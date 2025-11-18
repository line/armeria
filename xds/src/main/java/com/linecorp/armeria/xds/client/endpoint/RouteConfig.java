/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.client.HttpPreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;
import com.linecorp.armeria.xds.internal.DelegatingHttpClient;
import com.linecorp.armeria.xds.internal.DelegatingRpcClient;

final class RouteConfig {
    private final ListenerSnapshot listenerSnapshot;

    private final HttpPreClient httpPreClient;
    private final RpcPreClient rpcPreClient;
    private final VirtualHostMatcher virtualHostMatcher;

    RouteConfig(ListenerSnapshot listenerSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
        httpPreClient = listenerSnapshot.downstreamFilter().decorate(DelegatingHttpClient.of());
        rpcPreClient = listenerSnapshot.downstreamFilter().rpcDecorate(DelegatingRpcClient.of());
        virtualHostMatcher = new VirtualHostMatcher(listenerSnapshot);
    }

    ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    HttpPreClient httpPreClient() {
        return httpPreClient;
    }

    RpcPreClient rpcPreClient() {
        return rpcPreClient;
    }

    @Nullable
    RouteEntry select(PreClientRequestContext ctx) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return null;
        }
        final VirtualHostSnapshot virtualHostSnapshot = virtualHostMatcher.find(ctx);
        if (virtualHostSnapshot == null) {
            return null;
        }

        for (RouteEntry entry: virtualHostSnapshot.routeEntries()) {
            if (entry.matches(ctx)) {
                ctx.setAttr(XdsAttributeKeys.ROUTE_METADATA_MATCH,
                            entry.route().getRoute().getMetadataMatch());
                return entry;
            }
        }
        return null;
    }
}
