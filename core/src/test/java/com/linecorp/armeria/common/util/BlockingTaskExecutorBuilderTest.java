package com.linecorp.armeria.common.util;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.time.Duration;
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
    void testSetting() {
        final long keepAliveTime = 30 * 1000;
        final int numThreads = Flags.numCommonBlockingTaskThreads();

        final ScheduledThreadPoolExecutor pool =
                (ScheduledThreadPoolExecutor) BlockingTaskExecutor
                        .builder()
                        .allowThreadTimeOut(false)
                        .keepAliveTimeMillis(keepAliveTime)
                        .numThreads(numThreads)
                        .build()
                        .unwrap();

        assertThat(pool.allowsCoreThreadTimeOut()).isFalse();
        assertThat(pool.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(keepAliveTime);
        assertThat(pool.getCorePoolSize()).isEqualTo(numThreads);
    }
}
