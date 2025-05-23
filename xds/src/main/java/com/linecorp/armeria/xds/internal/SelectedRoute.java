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

package com.linecorp.armeria.xds.internal;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

public final class SelectedRoute implements ConfigSupplier {

    private final ClientDecoration upstreamFilter;
    private final ListenerSnapshot listenerSnapshot;
    private final RouteSnapshot routeSnapshot;
    private final VirtualHostSnapshot virtualHostSnapshot;
    private final RouteEntry routeEntry;

    SelectedRoute(ListenerSnapshot listenerSnapshot, RouteSnapshot routeSnapshot,
                  VirtualHostSnapshot virtualHostSnapshot, RouteEntry routeEntry) {
        this.listenerSnapshot = listenerSnapshot;
        this.routeSnapshot = routeSnapshot;
        this.virtualHostSnapshot = virtualHostSnapshot;
        this.routeEntry = routeEntry;
        upstreamFilter = FilterUtil.buildUpstreamFilter(this);
    }

    @Override
    public ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    @Override
    public RouteSnapshot routeSnapshot() {
        return routeSnapshot;
    }

    @Override
    public VirtualHostSnapshot virtualHostSnapshot() {
        return virtualHostSnapshot;
    }

    @Nullable
    @Override
    public ClusterSnapshot clusterSnapshot() {
        return routeEntry.clusterSnapshot();
    }

    public ClientDecoration upstreamFilter() {
        return upstreamFilter;
    }

    @Override
    public RouteEntry routeEntry() {
        return routeEntry;
    }
}
