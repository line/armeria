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

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.netty.util.AttributeKey;

public final class XdsAttributeKeys {

    public static final AttributeKey<Metadata> ROUTE_METADATA_MATCH =
            AttributeKey.valueOf(XdsAttributeKeys.class, "ROUTER_METADATA");
    public static final AttributeKey<RouteConfig> ROUTE_CONFIG =
            AttributeKey.valueOf(XdsAttributeKeys.class, "ROUTE_CONFIG");

    private XdsAttributeKeys() {}
}
