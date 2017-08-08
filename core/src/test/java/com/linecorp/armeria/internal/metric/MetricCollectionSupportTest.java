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

import org.junit.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MetricKeyFunction;
import com.linecorp.armeria.common.metric.Metrics;
import com.linecorp.armeria.common.metric.RequestMetrics;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

public class MetricCollectionSupportTest {

    @Test
    public void testMetricsForHttp() {
        final Metrics metrics = new Metrics();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), metrics, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/bar", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/bar"));

        final MetricKeyFunction metricKeyFunction = MetricKeyFunction.ofLabellessDefault();
        final RequestLog requestLog = ctx.log();

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C, "example.com");
        MetricCollectionSupport.setup(ctx, metricKeyFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/bar"));
        ctx.logBuilder().requestContent(null, null);
        ctx.logBuilder().requestLength(123);

        final RequestMetrics requestMetrics = metrics.group(metricKeyFunction.apply(requestLog),
                                                            RequestMetrics.class);
        assertThat(requestMetrics.active().value()).isOne();
        assertThat(requestMetrics.requestDuration().count()).isZero();
        assertThat(requestMetrics.requestDuration().sum()).isZero();
        assertThat(requestMetrics.requestLength().count()).isZero();
        assertThat(requestMetrics.requestLength().sum()).isZero();

        ctx.logBuilder().endRequest();
        assertThat(requestMetrics.requestDuration().count()).isOne();
        assertThat(requestMetrics.requestDuration().sum()).isGreaterThan(0);
        assertThat(requestMetrics.requestLength().count()).isOne();
        assertThat(requestMetrics.requestLength().sum()).isEqualTo(123);

        ctx.logBuilder().responseHeaders(HttpHeaders.of(200));
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endResponse();

        assertThat(requestMetrics.active().value()).isZero();
        assertThat(requestMetrics.success().value()).isOne();
        assertThat(requestMetrics.failure().value()).isZero();
        assertThat(requestMetrics.responseDuration().count()).isOne();
        assertThat(requestMetrics.responseDuration().sum()).isGreaterThan(0);
        assertThat(requestMetrics.responseLength().count()).isOne();
        assertThat(requestMetrics.responseLength().sum()).isEqualTo(456);
        assertThat(requestMetrics.totalDuration().count()).isOne();
        assertThat(requestMetrics.totalDuration().sum()).isGreaterThan(0);
    }
}
