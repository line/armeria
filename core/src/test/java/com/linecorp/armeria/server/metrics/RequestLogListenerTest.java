/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.metrics;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogListener;

public class RequestLogListenerTest {
    @Test
    public void testComposition() throws Exception {
        // Given
        final RequestLog mockRequest = mock(RequestLog.class);

        final RequestContext mockRequestContext = mock(RequestContext.class);

        final int[] executeCounters = { 0 };

        final RequestLogListener consumer = log -> executeCounters[0]++;

        final RequestLogListener finalConsumer = consumer.andThen(consumer).andThen(consumer);

        // When
        when(mockRequest.context()).thenReturn(mockRequestContext);
        when(mockRequestContext.push()).thenReturn(() -> {
        });
        finalConsumer.onRequestLog(mockRequest);

        // Then
        assertEquals("onRequestLog() should be invoked 3 times", 3, executeCounters[0]);
    }
}

