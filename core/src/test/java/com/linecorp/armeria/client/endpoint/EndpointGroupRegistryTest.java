/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class EndpointGroupRegistryTest {

    @Before
    @After
    public void setUp() {
        // Just in case the group 'foo3' was registered somewhere else.
        EndpointGroupRegistry.unregister("foo3");
    }

    @Test
    public void testRegistration() throws Exception {
        // Unregister a non-existent group.
        assertThat(EndpointGroupRegistry.unregister("foo3")).isFalse();

        final EndpointGroup group1 = new StaticEndpointGroup(Endpoint.of("a.com"));
        final EndpointGroup group2 = new StaticEndpointGroup(Endpoint.of("b.com"));

        // Register a new group.
        assertThat(EndpointGroupRegistry.register("foo3", group1, WEIGHTED_ROUND_ROBIN)).isTrue();
        assertThat(EndpointGroupRegistry.get("foo3")).isSameAs(group1);
        assertThat(EndpointGroupRegistry.get("fOO3")).isSameAs(group1); // Ensure case-insensitivity

        // Replace the group.
        assertThat(EndpointGroupRegistry.register("Foo3", group2, WEIGHTED_ROUND_ROBIN)).isFalse();
        assertThat(EndpointGroupRegistry.get("foo3")).isSameAs(group2);

        // Unregister the group.
        assertThat(EndpointGroupRegistry.unregister("FOO3")).isTrue();
        assertThat(EndpointGroupRegistry.get("foo3")).isNull();
    }

    @Test
    public void testBadGroupNames() throws Exception {
        final EndpointGroup g = mock(EndpointGroup.class);
        final EndpointSelectionStrategy s = EndpointSelectionStrategy.ROUND_ROBIN;
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a:b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a+b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a@b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a#b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a/b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a\\b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a?b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a*b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointGroupRegistry.register("a#b", g, s))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
