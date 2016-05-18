/*
 * Copyright 2016 LINE Corporation
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
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.DefaultResponseLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.metrics.MetricConsumer;

import io.netty.channel.embedded.EmbeddedChannel;

public class MetricConsumerTest {
    @Test
    public void testInfiniteRecursion() throws Exception {
        // Given
        final int[] executeCounters = { 0,   // for onRequest
                                        0 }; // for onResponse

        MetricConsumer consumer = new MetricConsumer() {
            @Override
            public void onRequest(RequestLog log) {
                executeCounters[0]++;
            }

            @Override
            public void onResponse(ResponseLog log) {
                executeCounters[1]++;
            }
        };
        MetricConsumer finalConsumer = consumer.andThen(consumer).andThen(consumer);

        final DefaultRequestLog reqLog = new DefaultRequestLog();
        final DefaultResponseLog resLog = new DefaultResponseLog(reqLog);

        reqLog.start(new EmbeddedChannel(), SessionProtocol.H2C, "localhost", "GET", "/");
        reqLog.end();
        resLog.start();
        resLog.end();

        // When
        finalConsumer.onRequest(reqLog);
        finalConsumer.onResponse(resLog);

        // Then
        assertEquals("onRequest should be invoked 3 times", 3, executeCounters[0]);
        assertEquals("onResponse should be invoked 3 times", 3, executeCounters[1]);
    }
}

