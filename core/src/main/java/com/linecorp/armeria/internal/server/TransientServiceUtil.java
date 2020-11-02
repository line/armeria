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
package com.linecorp.armeria.internal.server;

import static com.linecorp.armeria.server.TransientService.ActionType.ACCESS_LOGGING;
import static com.linecorp.armeria.server.TransientService.ActionType.GRACEFUL_SHUTDOWN;
import static com.linecorp.armeria.server.TransientService.ActionType.LOGGING;
import static com.linecorp.armeria.server.TransientService.ActionType.METRIC_COLLECTION;

import java.util.EnumMap;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientService;
import com.linecorp.armeria.server.TransientService.ActionType;

public final class TransientServiceUtil {

    private static final EnumMap<ActionType, Boolean> defaultTransientServiceActions =
            Maps.newEnumMap(ImmutableMap.of(GRACEFUL_SHUTDOWN, false,
                                            METRIC_COLLECTION, false,
                                            LOGGING, false,
                                            ACCESS_LOGGING, false));

    /**
     * Returns the default {@link TransientService} actions.
     */
    public static EnumMap<ActionType, Boolean> defaultTransientServiceActions() {
        return defaultTransientServiceActions;
    }

    /**
     * Tells whether the specified {@link ActionType} is enabled for the {@link Service}
     * in the specified {@link ServiceRequestContext}.
     * If the {@link Service} is not a {@link TransientService}, this returns {@code true}.
     */
    public static boolean countFor(ServiceRequestContext ctx, ActionType type) {
        @SuppressWarnings("rawtypes")
        final TransientService transientService = ctx.config().service().as(TransientService.class);
        return transientService == null || transientService.countFor(type);
    }

    private TransientServiceUtil() {}
}
