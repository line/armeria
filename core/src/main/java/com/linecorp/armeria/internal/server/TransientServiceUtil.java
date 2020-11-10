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

import static com.linecorp.armeria.server.OptOutFeature.ACCESS_LOGGING;
import static com.linecorp.armeria.server.OptOutFeature.GRACEFUL_SHUTDOWN;
import static com.linecorp.armeria.server.OptOutFeature.LOGGING;
import static com.linecorp.armeria.server.OptOutFeature.METRIC_COLLECTION;

import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.server.OptOutFeature;
import com.linecorp.armeria.server.TransientService;

public final class TransientServiceUtil {

    private static final Set<OptOutFeature> defaultoptOutFeatures =
            Sets.newEnumSet(ImmutableSet.of(GRACEFUL_SHUTDOWN, METRIC_COLLECTION, LOGGING, ACCESS_LOGGING),
                            OptOutFeature.class);

    /**
     * Returns the default {@link TransientService} actions.
     */
    public static Set<OptOutFeature> defaultoptOutFeatures() {
        return defaultoptOutFeatures;
    }

    private TransientServiceUtil() {}
}
