package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

public class RingHashEndpointSelectionStrategyTest {
    private static final EndpointGroup emptyGroup = EndpointGroup.of();

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void select() {
        assertThat(EndpointGroup.of(Endpoint.parse("localhost:1234"),
                                    Endpoint.parse("localhost:2345"))
                                .selectNow(ctx)).isNotNull();

        assertThat(emptyGroup.selectNow(ctx)).isNull();
    }
}
