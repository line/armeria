/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.consul;

import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Utility methods related to Consul clients.
 */
final class ConsulClientUtil {

    private static final String DATACENTER_PARAM = "dc";
    private static final String FILTER_PARAM = "filter";

    /**
     * Encodes common Consul API parameters as {@code QueryParams}.
     */
    static QueryParams queryParams(@Nullable String datacenter, @Nullable String filter) {
        final QueryParamsBuilder paramsBuilder = QueryParams.builder();
        if (datacenter != null) {
            paramsBuilder.add(DATACENTER_PARAM, datacenter);
        }
        if (filter != null) {
            paramsBuilder.add(FILTER_PARAM, filter);
        }
        return paramsBuilder.build();
    }

    private ConsulClientUtil() {}
}
