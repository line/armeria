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

import com.linecorp.armeria.xds.internal.XdsAttributeKeys;

import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.netty.util.AttributeKey;

final class ClientXdsAttributeKeys {

    static final AttributeKey<LbEndpoint> LB_ENDPOINT_KEY =
            AttributeKey.valueOf(XdsAttributeKeys.class, "LB_ENDPOINT_KEY");
    static final AttributeKey<LocalityLbEndpoints> LOCALITY_LB_ENDPOINTS_KEY =
            AttributeKey.valueOf(XdsAttributeKeys.class, "LOCALITY_LB_ENDPOINTS_KEY");
    static final AttributeKey<XdsRandom> XDS_RANDOM =
            AttributeKey.valueOf(XdsAttributeKeys.class, "XDS_RANDOM");

    private ClientXdsAttributeKeys() {}
}
