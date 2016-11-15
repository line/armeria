package com.linecorp.armeria.client.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class EndpointGroupTest {
    @Test
    public void orElse() throws Exception {
        EndpointGroup emptyEndpointGroup = new StaticEndpointGroup();
        EndpointGroup endpointGroup1 = new StaticEndpointGroup(Endpoint.of("127.0.0.1", 1234));
        EndpointGroup endpointGroup2 = new StaticEndpointGroup(Endpoint.of("127.0.0.1", 2345));

        assertThat(emptyEndpointGroup.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup2.endpoints());
        assertThat(endpointGroup1.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup1.endpoints());
    }
}
