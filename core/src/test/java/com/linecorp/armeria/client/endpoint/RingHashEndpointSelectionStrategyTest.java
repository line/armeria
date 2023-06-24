/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ringHash;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.RingHashEndpointSelectionStrategy.RingHashSelector;
import com.linecorp.armeria.client.endpoint.RingHashEndpointSelectionStrategy.RingHashSelector.WeightedRingEndpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyTest.EndpointComparator;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

public class RingHashEndpointSelectionStrategyTest {

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void testRingHashSelect() {
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345);
        final EndpointGroup group = EndpointGroup.of(ringHash(), foo, bar);

        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = group.selectNow(ctx);
            assert endpoint != null;
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }

    @Test
    void testWeightRingHashSelect() {
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234).withWeight(1);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345).withWeight(2);
        final EndpointGroup group = EndpointGroup.of(ringHash(), foo, bar);

        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = group.selectNow(ctx);
            assert endpoint != null;
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }

    @Test
    void testWeightRingHashSelectWithSize() {
        final RingHashEndpointSelectionStrategy instance = RingHashEndpointSelectionStrategy.INSTANCE;
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234).withWeight(1);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345).withWeight(2);
        final EndpointGroup group = EndpointGroup.of(foo, bar);
        final EndpointSelector endpointSelector = instance.newSelector(group, 3);
        final RingHashSelector ringHashSelector = (RingHashSelector) endpointSelector;
        final WeightedRingEndpoint weightedRingEndpoint = ringHashSelector.weightedRingEndpoint;
        assert weightedRingEndpoint != null;
        final Int2ObjectSortedMap<Endpoint> ring = weightedRingEndpoint.ring;
        Assertions.assertEquals(3, ring.size());
        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = endpointSelector.selectNow(ctx);
            assert endpoint != null;
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }
}
