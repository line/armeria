package com.linecorp.armeria.common.util;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;

class BlockingTaskExecutorBuilderTest {

    @Test
    void testDefault() {
        final ScheduledThreadPoolExecutor pool =
                (ScheduledThreadPoolExecutor) BlockingTaskExecutor.builder().build().unwrap();
        assertThat(pool.allowsCoreThreadTimeOut()).isTrue();
        assertThat(pool.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(60 * 1000);
        assertThat(pool.getCorePoolSize()).isEqualTo(Flags.numCommonBlockingTaskThreads());
    }

    @Test
    void testOverride() {
        final long keepAliveTime = 42 * 1000;
        final int numThreads = 42;

        final ScheduledThreadPoolExecutor pool =
                (ScheduledThreadPoolExecutor) BlockingTaskExecutor
                        .builder()
                        .keepAliveTimeMillis(keepAliveTime)
                        .numThreads(numThreads)
                        .build()
                        .unwrap();

        assertThat(pool.allowsCoreThreadTimeOut()).isTrue();
        assertThat(pool.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(keepAliveTime);
        assertThat(pool.getCorePoolSize()).isEqualTo(numThreads);
    }
}
