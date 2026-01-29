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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.AbstractAsyncSelector;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsResourceException;

final class RouteConfigSelector extends AbstractAsyncSelector<RouteConfig>
        implements SnapshotWatcher<ListenerSnapshot> {

    @Nullable
    private volatile RouteConfig routeConfig;

    RouteConfigSelector(ListenerRoot listenerRoot) {
        listenerRoot.addSnapshotWatcher(this);
    }

    @Override
    @Nullable
    protected RouteConfig selectNow(ClientRequestContext ctx) {
        return routeConfig;
    }

    @Override
    public void onUpdate(@Nullable ListenerSnapshot snapshot, @Nullable XdsResourceException t) {
        if (snapshot == null) {
            return;
        }
        routeConfig = new RouteConfig(snapshot);
        refresh();
    }
}
