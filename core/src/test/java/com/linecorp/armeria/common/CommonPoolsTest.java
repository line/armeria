/*
 *  Copyright 2025 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.util.EventLoopGroups;

import io.micrometer.core.instrument.binder.netty4.NettyMeters;
import io.netty.channel.EventLoopGroup;

public class CommonPoolsTest {
    @Test
    public void testEventLoopMetricsBinding() throws Exception {
        final EventLoopGroup testGroup = EventLoopGroups.newEventLoopGroup(1);

        try {
            CommonPools.bindEventLoopMetricsForWorkerGroup(testGroup);

            // verify that registry contains the newly added metrics
            final Map<String, Double> registeredMeters = MoreMeters.measureAll(Flags.meterRegistry());
            assertThat(registeredMeters.keySet()
                                       .stream()
                                       .anyMatch(key -> key.startsWith(
                                               NettyMeters.EVENT_EXECUTOR_TASKS_PENDING.getName())))
                    .isTrue();
        } finally {
            testGroup.shutdownNow();
        }
    }
}
