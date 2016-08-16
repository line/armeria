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
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.MessageLogConsumer;

public class MessageLogConsumerTest {
    @Test
    public void testComposition() throws Exception {
        // Given
        final int[] executeCounters = { 0,   // for onRequest
                                        0 }; // for onResponse

        MessageLogConsumer consumer = new MessageLogConsumer() {
            @Override
            public void onRequest(RequestContext ctx, RequestLog req) {
                executeCounters[0]++;
            }

            @Override
            public void onResponse(RequestContext ctx, ResponseLog res) {
                executeCounters[1]++;
            }
        };

        MessageLogConsumer finalConsumer = consumer.andThen(consumer).andThen(consumer);

        // When
        final RequestContext ctx = mock(RequestContext.class);
        finalConsumer.onRequest(ctx, mock(RequestLog.class));
        finalConsumer.onResponse(ctx, mock(ResponseLog.class));

        // Then
        assertEquals("onRequest should be invoked 3 times", 3, executeCounters[0]);
        assertEquals("onResponse should be invoked 3 times", 3, executeCounters[1]);
    }
}

