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
package com.linecorp.armeria.internal.nacos;

import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Utility methods related to Nacos clients.
 */
final class NacosClientUtil {
    private static final String NAMESPACE_ID_PARAM = "namespaceId";

    private static final String GROUP_NAME_PARAM = "groupName";

    private static final String SERVICE_NAME_PARAM = "serviceName";

    private static final String CLUSTER_NAME_PARAM = "clusterName";

    private static final String HEALTHY_ONLY_PARAM = "healthyOnly";

    private static final String APP_PARAM = "app";

    private static final String IP_PARAM = "ip";

    private static final String PORT_PARAM = "port";

    private static final String WEIGHT_PARAM = "weight";

    /**
     * Encodes common Nacos API parameters as {@code QueryParams}.
     */
    static QueryParams queryParams(@Nullable String namespaceId, @Nullable String groupName,
                                   @Nullable String serviceName, @Nullable String clusterName,
                                   @Nullable Boolean healthyOnly, @Nullable String app,
                                   @Nullable String ip, @Nullable Integer port, @Nullable Integer weight) {
        final QueryParamsBuilder paramsBuilder = QueryParams.builder();
        if (namespaceId != null) {
            paramsBuilder.add(NAMESPACE_ID_PARAM, namespaceId);
        }
        if (groupName != null) {
            paramsBuilder.add(GROUP_NAME_PARAM, groupName);
        }
        if (serviceName != null) {
            paramsBuilder.add(SERVICE_NAME_PARAM, serviceName);
        }
        if (clusterName != null) {
            paramsBuilder.add(CLUSTER_NAME_PARAM, clusterName);
        }
        if (healthyOnly != null) {
            paramsBuilder.add(HEALTHY_ONLY_PARAM, healthyOnly.toString());
        }
        if (app != null) {
            paramsBuilder.add(APP_PARAM, app);
        }
        if (ip != null) {
            paramsBuilder.add(IP_PARAM, ip);
        }
        if (port != null) {
            paramsBuilder.add(PORT_PARAM, port.toString());
        }
        if (weight != null) {
            paramsBuilder.add(WEIGHT_PARAM, weight.toString());
        }
        return paramsBuilder.build();
    }

    private NacosClientUtil() {}
}
