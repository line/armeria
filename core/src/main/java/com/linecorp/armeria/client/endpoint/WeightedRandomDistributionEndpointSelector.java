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
package com.linecorp.armeria.client.endpoint;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightedRandomDistributionEndpointSelector.Entry;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.endpoint.WeightedRandomDistributionSelector;

/**
 * This selector selects an {@link Endpoint} using random and the weight of the {@link Endpoint}. If there are
 * A(weight 10), B(weight 4) and C(weight 6) {@link Endpoint}s, the chances that {@link Endpoint}s are selected
 * are 10/20, 4/20 and 6/20, respectively. If {@link Endpoint} A is selected 10 times and B and C are not
 * selected as much as their weight, then A is removed temporarily and the chances that B and C are selected
 * are 4/10 and 6/10.
 */
final class WeightedRandomDistributionEndpointSelector
        extends WeightedRandomDistributionSelector<Entry> {

    WeightedRandomDistributionEndpointSelector(List<Endpoint> endpoints) {
        super(mapEndpoints(endpoints));
    }

    private static List<Entry> mapEndpoints(List<Endpoint> endpoints) {
        return endpoints.stream().map(Entry::new).collect(ImmutableList.toImmutableList());
    }

    @Nullable
    Endpoint selectEndpoint() {
        final Entry entry = select();
        if (entry == null) {
            return null;
        }
        return entry.endpoint();
    }

    @VisibleForTesting
    static final class Entry extends AbstractEntry {

        private final Endpoint endpoint;

        Entry(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        @Override
        public int weight() {
            return endpoint().weight();
        }
    }
}
