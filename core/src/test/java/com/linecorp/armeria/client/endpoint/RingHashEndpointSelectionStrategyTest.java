package com.linecorp.armeria.client.endpoint;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ringHash;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyTest.EndpointComparator;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

public class RingHashEndpointSelectionStrategyTest {

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void select() {
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345);
        final EndpointGroup group = EndpointGroup.of(ringHash(), foo, bar);

        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = group.selectNow(ctx);
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }

    @Test
    void select2() {
        final Endpoint foo = Endpoint.of("127.0.0.1", 1234).withWeight(1);
        final Endpoint bar = Endpoint.of("127.0.0.1", 2345).withWeight(2);
        final EndpointGroup group = EndpointGroup.of(ringHash(), foo, bar);

        final List<Endpoint> selected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Endpoint endpoint = group.selectNow(ctx);
            selected.add(endpoint);
        }

        assertThat(selected).usingElementComparator(EndpointComparator.INSTANCE).containsAnyOf(
                foo, bar
        );
    }
}
