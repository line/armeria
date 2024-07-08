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

package com.linecorp.armeria.internal.client.endpoint.healthcheck;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

public final class HealthCheckAttributes {

    public static final AttributeKey<Boolean> HEALTHY_ATTR =
            AttributeKey.valueOf(HealthCheckAttributes.class, "HEALTHY");
    public static final AttributeKey<Boolean> DEGRADED_ATTR =
            AttributeKey.valueOf(HealthCheckAttributes.class, "DEGRADED");

    @Nullable
    public static Boolean healthy(Endpoint endpoint) {
        return endpoint.attr(HEALTHY_ATTR);
    }

    @Nullable
    public static Boolean degraded(Endpoint endpoint) {
        return endpoint.attr(DEGRADED_ATTR);
    }

    private HealthCheckAttributes() {}
}
