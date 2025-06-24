package com.linecorp.armeria.common;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import com.linecorp.armeria.common.util.EventLoopGroups;

import io.micrometer.core.instrument.binder.netty4.NettyEventExecutorMetrics;
import io.netty.channel.EventLoopGroup;

public class CommonPoolsTest {
    @Test
    public void testEventLoopMetricsBinding() throws Exception {
        final EventLoopGroup testGroup = EventLoopGroups.newEventLoopGroup(1);

        try {
            try (MockedConstruction<NettyEventExecutorMetrics> microMeterEventloopMetrics =
                         Mockito.mockConstruction(NettyEventExecutorMetrics.class)) {
                CommonPools.bindEventLoopMetricsForWorkerGroup(testGroup);

                assertThat(microMeterEventloopMetrics.constructed().isEmpty()).isFalse();

                final NettyEventExecutorMetrics instance = microMeterEventloopMetrics.constructed().get(0);
                verify(instance).bindTo(any());
            }
        } finally {
            testGroup.shutdownNow();
        }
    }
}
