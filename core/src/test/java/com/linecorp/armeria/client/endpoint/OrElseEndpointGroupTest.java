package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class OrElseEndpointGroupTest {
    @Test
    public void updateFirstEndpoints() {
        DynamicEndpointGroup firstEndpointGroup = new DynamicEndpointGroup();
        DynamicEndpointGroup secondEndpointGroup = new DynamicEndpointGroup();
        EndpointGroup endpointGroup = new OrElseEndpointGroup(firstEndpointGroup, secondEndpointGroup);

        firstEndpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                         Endpoint.of("127.0.0.1", 2222)));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222)));

        firstEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 3333));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        firstEndpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333)));
    }

    @Test
    public void updateSecondEndpoints() {
        DynamicEndpointGroup firstEndpointGroup = new DynamicEndpointGroup();
        DynamicEndpointGroup secondEndpointGroup = new DynamicEndpointGroup();
        EndpointGroup endpointGroup = new OrElseEndpointGroup(firstEndpointGroup, secondEndpointGroup);

        secondEndpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                          Endpoint.of("127.0.0.1", 2222)));

        secondEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 3333));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        secondEndpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        firstEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 4444));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 4444)));

        // Use firstEndpointGroup's endpoint list even if secondEndpointGroup has change.
        secondEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 5555));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 4444)));

        // Fallback to secondEndpointGroup if firstEndpointGroup has no endpoints.
        firstEndpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 4444));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333),
                                                                         Endpoint.of("127.0.0.1", 5555)));
    }
}
