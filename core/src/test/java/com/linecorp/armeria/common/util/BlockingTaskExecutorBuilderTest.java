/*
 * Copyright 2021 LINE Corporation
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
