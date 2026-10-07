/*
 * Copyright 2026 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.spring.ArmeriaSettings.InternalServiceProperties;
import com.linecorp.armeria.spring.InternalServiceId;

/**
 * A utility class which resolves the {@link InternalServiceId}s from {@link InternalServiceProperties}.
 */
public final class InternalServiceIdUtil {

    private static final List<InternalServiceId> ALL_SERVICE_IDS =
            ImmutableList.of(InternalServiceId.DOCS, InternalServiceId.HEALTH,
                             InternalServiceId.METRICS, InternalServiceId.ACTUATOR);

    /**
     * Returns {@code include} minus {@code exclude}, with {@link InternalServiceId#ALL} expanded.
     */
    public static List<InternalServiceId> resolveServiceIds(InternalServiceProperties properties) {
        requireNonNull(properties, "properties");
        final List<InternalServiceId> include = expand(properties.getInclude());
        if (include.isEmpty()) {
            return include;
        }
        final List<InternalServiceId> exclude = expand(properties.getExclude());
        if (exclude.isEmpty()) {
            return include;
        }
        return include.stream()
                      .filter(id -> !exclude.contains(id))
                      .collect(toImmutableList());
    }

    private static List<InternalServiceId> expand(@Nullable List<InternalServiceId> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return ImmutableList.of();
        }
        if (serviceIds.contains(InternalServiceId.ALL)) {
            return ALL_SERVICE_IDS;
        }
        return ImmutableList.copyOf(serviceIds);
    }

    private InternalServiceIdUtil() {}
}
