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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.RingHashEndpointSelectionStrategy.RingHashSelector;
import com.linecorp.armeria.client.endpoint.RingHashEndpointSelectionStrategy.RingHashSelector.WeightedRingEndpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyTest.EndpointComparator;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

class RingHashEndpointSelectionStrategyTest {

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void testRingHashSelect() {
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345);
        final EndpointGroup group = EndpointGroup.of(ringHash(), foo, bar);

        assertThat(group.selectionStrategy()).isSameAs(ringHash());
        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = group.selectNow(ctx);
            assertThat(endpoint).isNotNull();
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
            assertThat(endpoint).isNotNull();
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

        assertThat(weightedRingEndpoint).isNotNull();

        final Int2ObjectSortedMap<Endpoint> ring = weightedRingEndpoint.ring;
        Assertions.assertEquals(3, ring.size());
        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = endpointSelector.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }

    @Test
    void testWeightRingHashSelectBinarySearch() {
        // If the ring has a size of 5 and each item have weights of 3, 2, and 6,
        // then each item on the ring should be placed 1, 1, and 2 times
        // as many times as the remainder divided by 3.
        final RingHashEndpointSelectionStrategy instance = RingHashEndpointSelectionStrategy.INSTANCE;
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234).withWeight(3); // 1
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345).withWeight(2); // 1
        final Endpoint baz = Endpoint.of("127.0.0.1", 3456).withWeight(6); // 2
        final EndpointGroup group = EndpointGroup.of(foo, bar, baz);
        final EndpointSelector endpointSelector = instance.newSelector(group, 4);
        final RingHashSelector ringHashSelector = (RingHashSelector) endpointSelector;
        final WeightedRingEndpoint weightedRingEndpoint = ringHashSelector.weightedRingEndpoint;

        assertThat(weightedRingEndpoint).isNotNull();

        final Int2ObjectSortedMap<Endpoint> ring = weightedRingEndpoint.ring;
        Assertions.assertEquals(4, ring.size());
        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = endpointSelector.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar, baz
        );
    }

    @Test
    void selectFromDynamicEndpointGroup() {
        final TestDynamicEndpointGroup group = new TestDynamicEndpointGroup();
        group.updateEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1000)));
        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1000));

        final Endpoint foo = Endpoint.of("127.0.0.1", 1111).withWeight(1);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2222).withWeight(2);
        group.updateEndpoints(ImmutableList.of(foo, bar));

        final RingHashEndpointSelectionStrategy instance = RingHashEndpointSelectionStrategy.INSTANCE;
        final EndpointSelector endpointSelector = instance.newSelector(group, 3);
        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = endpointSelector.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }

    private static final class TestDynamicEndpointGroup extends DynamicEndpointGroup {
        void updateEndpoints(final List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }
    }
}
