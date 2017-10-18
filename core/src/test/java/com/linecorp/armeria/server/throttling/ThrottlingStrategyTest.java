package com.linecorp.armeria.server.throttling;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

public class ThrottlingStrategyTest {

    @Test
    public void name() {
        assertThat(ThrottlingStrategy.always().name()).isEqualTo("throttling-strategy-always");
        assertThat(ThrottlingStrategy.never().name()).isEqualTo("throttling-strategy-never");
        assertThat(ThrottlingStrategy.of((ctx, req) -> completedFuture(false)).name())
                .isEqualTo("throttling-strategy-2");
        assertThat(new TestThrottlingStrategy().name())
                .isEqualTo("throttling-strategy-TestThrottlingStrategy");
    }

    private static class TestThrottlingStrategy extends ThrottlingStrategy<RpcRequest> {
        @Override
        public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, RpcRequest request) {
            return completedFuture(true);
        }
    }
}
