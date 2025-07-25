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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.ParsedFilterConfig;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.VirtualHostSnapshot;

interface ConfigSupplier {

    @Nullable
    default ParsedFilterConfig config(String typeUrl) {
        ParsedFilterConfig config = routeEntry().filterConfig(typeUrl);
        if (config != null) {
            return config;
        }
        config = virtualHostSnapshot().xdsResource().filterConfig(typeUrl);
        if (config != null) {
            return config;
        }
        return routeSnapshot().xdsResource().filterConfig(typeUrl);
    }

    ListenerSnapshot listenerSnapshot();

    RouteSnapshot routeSnapshot();

    VirtualHostSnapshot virtualHostSnapshot();

    @Nullable
    ClusterSnapshot clusterSnapshot();

    RouteEntry routeEntry();
}
