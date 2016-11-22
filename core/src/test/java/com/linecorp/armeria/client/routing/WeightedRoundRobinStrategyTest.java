package com.linecorp.armeria.client.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class WeightedRoundRobinStrategyTest {
    private static final EndpointGroup ENDPOINT_GROUP = new StaticEndpointGroup(Endpoint.of("localhost:1234"),
                                                                                Endpoint.of("localhost:2345"));
    private static final EndpointGroup EMPTY_ENDPOINT_GROUP = new StaticEndpointGroup();

    private final WeightedRoundRobinStrategy strategy = new WeightedRoundRobinStrategy();

    @Before
    public void setup() {
        EndpointGroupRegistry.register("endpoint", ENDPOINT_GROUP, strategy);
        EndpointGroupRegistry.register("empty", EMPTY_ENDPOINT_GROUP, strategy);
    }

    @Test
    public void select() {
        assertThat(EndpointGroupRegistry.selectNode("endpoint")).isNotNull();

        assertThat(catchThrowable(() -> EndpointGroupRegistry.selectNode("empty")))
                .isInstanceOf(EndpointGroupException.class);
    }

}
