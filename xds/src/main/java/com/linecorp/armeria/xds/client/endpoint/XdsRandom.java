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

import java.util.concurrent.ThreadLocalRandom;

interface XdsRandom {

    XdsRandom DEFAULT = new XdsRandom() {};

    enum RandomHint {
        SELECT_PRIORITY,
        ROUTING_ENABLED,
        LOCAL_PERCENTAGE,
        LOCAL_THRESHOLD,
        ALL_RESIDUAL_ZERO,
    }

    default int nextInt(int bound, RandomHint randomHint) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    default long nextLong(long bound, RandomHint randomHint) {
        return ThreadLocalRandom.current().nextLong(bound);
    }
}
