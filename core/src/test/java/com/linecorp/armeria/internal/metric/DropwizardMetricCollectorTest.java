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

package com.linecorp.armeria.internal.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Function;

import org.junit.Test;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;

public class DropwizardMetricCollectorTest {

    private static final Function<RequestLog, String> metricNameFunc =
            log -> "foo." + log.path() + '#' + log.method();

    @Test
    public void testMetricsForHttp() {
        final MetricRegistry registry = new MetricRegistry();
        final DropwizardMetricCollector collector =
                new DropwizardMetricCollector(registry, metricNameFunc);

        final RequestLog requestLog = mock(RequestLog.class);

        when(requestLog.sessionProtocol()).thenReturn(SessionProtocol.HTTP);
        when(requestLog.serializationFormat()).thenReturn(SerializationFormat.NONE);
        when(requestLog.path()).thenReturn("/bar");
        when(requestLog.method()).thenReturn(HttpMethod.GET);

        collector.onRequestStart(requestLog);
        assertThat(counter(registry, "foo./bar#GET.activeRequests").getCount()).isEqualTo(1);

        when(requestLog.requestLength()).thenReturn(123L);
        collector.onRequestEnd(requestLog);
        assertThat(meter(registry, "foo./bar#GET.requestBytes").getCount()).isEqualTo(123);

        when(requestLog.statusCode()).thenReturn(200);
        when(requestLog.responseLength()).thenReturn(456L);
        when(requestLog.totalDurationNanos()).thenReturn(13L);

        collector.onResponse(requestLog);
        assertThat(timer(registry, "foo./bar#GET.requests").getCount()).isEqualTo(1);
        assertThat(timer(registry, "foo./bar#GET.requests").getSnapshot().getValues()).containsExactly(13);
        assertThat(meter(registry, "foo./bar#GET.successes").getCount()).isEqualTo(1);
        assertThat(meter(registry, "foo./bar#GET.failures").getCount()).isEqualTo(0);
        assertThat(meter(registry, "foo./bar#GET.responseBytes").getCount()).isEqualTo(456);
        assertThat(counter(registry, "foo./bar#GET.activeRequests").getCount()).isEqualTo(0);
    }

    private static Counter counter(MetricRegistry metricRegistry, String key) {
        return metricRegistry.getCounters().get(key);
    }

    private static Meter meter(MetricRegistry metricRegistry, String key) {
        return metricRegistry.getMeters().get(key);
    }

    private static Timer timer(MetricRegistry metricRegistry, String key) {
        return metricRegistry.getTimers().get(key);
    }
}
