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

package com.linecorp.armeria.internal.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.measureAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.DefaultRpcRequest;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

public class RequestMetricSupportTest {

    @Test
    public void httpSuccess() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/foo", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/foo"));

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C);
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestContent(null, null);
        ctx.logBuilder().requestLength(123);

        Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 1.0);

        ctx.logBuilder().responseHeaders(HttpHeaders.of(200));
        ctx.logBuilder().responseLength(456);

        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 0.0)
                                .containsEntry("foo.requests#count{httpStatus=200,method=POST,result=success}",
                                               1.0)
                                .containsEntry("foo.requests#count{httpStatus=200,method=POST,result=failure}",
                                               0.0)
                                .containsEntry("foo.responseDuration#count{httpStatus=200,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#count{httpStatus=200,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#total{httpStatus=200,method=POST}", 456.0)
                                .containsEntry("foo.totalDuration#count{httpStatus=200,method=POST}", 1.0);
    }

    @Test
    public void httpFailure() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/foo", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/foo"));

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C);
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().responseHeaders(HttpHeaders.of(500));
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 0.0)
                                .containsEntry("foo.requests#count{httpStatus=500,method=POST,result=success}",
                                               0.0)
                                .containsEntry("foo.requests#count{httpStatus=500,method=POST,result=failure}",
                                               1.0)
                                .containsEntry("foo.responseDuration#count{httpStatus=500,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#count{httpStatus=500,method=POST}", 1.0)
                                .containsEntry("foo.totalDuration#count{httpStatus=500,method=POST}", 1.0);
    }

    @Test
    public void rpc() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/bar", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/bar"));

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("bar");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C);
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/bar"));
        ctx.logBuilder().requestContent(new DefaultRpcRequest(Object.class, "baz"), null);

        assertThat(measureAll(registry)).containsEntry("bar.activeRequests#value{method=baz}", 1.0);
    }
}
