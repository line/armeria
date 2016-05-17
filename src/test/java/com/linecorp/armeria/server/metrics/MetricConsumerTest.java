/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.metrics;

import static org.junit.Assert.assertEquals;

import java.util.Optional;

import org.junit.Test;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metrics.MetricConsumer;

public class MetricConsumerTest {
    @Test
    public void testInfiniteRecursion() throws Exception {
        // Given
        final int[] executeCounters = { 0,   // for invocationStarted
                                        0 }; // for invocationComplete

        MetricConsumer consumer = new MetricConsumer() {
            @Override
            public void invocationStarted(Scheme scheme, String hostname, String path, Optional<String> method) {
                executeCounters[0] += 1;
            }

            @Override
            public void invocationComplete(Scheme scheme, int code, long processTimeNanos, int requestSize,
                                           int responseSize, String hostname, String path,
                                           Optional<String> method, boolean started) {
                executeCounters[1] += 1;
            }
        };
        MetricConsumer finalConsumer = consumer.andThen(consumer).andThen(consumer);

        // When
        finalConsumer.invocationStarted(
                Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP), "", "", Optional.empty());
        finalConsumer.invocationComplete(
                Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP), 200, 0, 0, 0, "", "",
                Optional.of(""), true);

        // Then
        assertEquals("invocationStarted should be executed twice", 3, executeCounters[0]);
        assertEquals("invocationComplete should be executed twice", 3, executeCounters[1]);
    }
}

