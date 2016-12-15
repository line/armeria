/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.endpoint;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class EndpointGroupRegistryTest {

    @Before
    @After
    public void setUp() {
        // Just in case the group 'foo' was registered somewhere.
        EndpointGroupRegistry.unregister("foo");
    }

    @Test
    public void testRegistration() throws Exception {
        // Unregister a non-existent group.
        assertThat(EndpointGroupRegistry.unregister("foo")).isFalse();

        final EndpointGroup group1 = new StaticEndpointGroup(Endpoint.of("a.com"));
        final EndpointGroup group2 = new StaticEndpointGroup(Endpoint.of("b.com"));

        // Register a new group.
        assertThat(EndpointGroupRegistry.register("foo", group1, WEIGHTED_ROUND_ROBIN)).isTrue();
        assertThat(EndpointGroupRegistry.get("foo")).isSameAs(group1);

        // Replace the group.
        assertThat(EndpointGroupRegistry.register("foo", group2, WEIGHTED_ROUND_ROBIN)).isFalse();
        assertThat(EndpointGroupRegistry.get("foo")).isSameAs(group2);

        // Unregister the group.
        assertThat(EndpointGroupRegistry.unregister("foo")).isTrue();
        assertThat(EndpointGroupRegistry.get("foo")).isNull();
    }
}
