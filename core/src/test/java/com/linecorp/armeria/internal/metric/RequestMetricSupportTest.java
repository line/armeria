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

import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measure;
import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measureAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import com.linecorp.armeria.common.metric.MeterIdFunction;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

public class RequestMetricSupportTest {

    @Test
    public void http() {
        final MeterRegistry registry = new PrometheusMeterRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/foo", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/foo"));

        final MeterIdFunction meterIdFunction = MeterIdFunction.ofDefault("foo");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C, "example.com");
        RequestMetricSupport.setup(ctx, meterIdFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestContent(null, null);
        ctx.logBuilder().requestLength(123);

        assertThat(measure(registry, "foo_active_requests", "method", "POST")).isOne();
        assertThat(measureAll(registry, "foo_request_duration_seconds", "method", "POST")
                           .get("count")).isZero();
        assertThat(measureAll(registry, "foo_request_duration_seconds", "method", "POST")
                           .get("sum")).isZero();
        assertThat(measureAll(registry, "foo_request_length_bytes", "method", "POST")
                           .get("count")).isZero();
        assertThat(measureAll(registry, "foo_request_length_bytes", "method", "POST")
                           .get("sum")).isZero();

        ctx.logBuilder().endRequest();
        assertThat(measureAll(registry, "foo_request_duration_seconds", "method", "POST")
                           .get("count")).isOne();
        assertThat(measureAll(registry, "foo_request_duration_seconds", "method", "POST")
                           .get("sum")).isGreaterThan(0);
        assertThat(measureAll(registry, "foo_request_length_bytes", "method", "POST")
                           .get("count")).isOne();
        assertThat(measureAll(registry, "foo_request_length_bytes", "method", "POST")
                           .get("sum")).isEqualTo(123);

        ctx.logBuilder().responseHeaders(HttpHeaders.of(200));
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endResponse();

        assertThat(measure(registry, "foo_active_requests", "method", "POST")).isZero();
        assertThat(measure(registry, "foo_requests_total", "method", "POST", "result", "success")).isOne();
        assertThat(measure(registry, "foo_requests_total", "method", "POST", "result", "failure")).isZero();
        assertThat(measureAll(registry, "foo_response_duration_seconds", "method", "POST")
                           .get("count")).isOne();
        assertThat(measureAll(registry, "foo_response_duration_seconds", "method", "POST")
                           .get("sum")).isGreaterThan(0);
        assertThat(measureAll(registry, "foo_response_length_bytes", "method", "POST")
                           .get("count")).isOne();
        assertThat(measureAll(registry, "foo_response_length_bytes", "method", "POST")
                           .get("sum")).isEqualTo(456);
        assertThat(measureAll(registry, "foo_total_duration_seconds", "method", "POST")
                           .get("count")).isOne();
        assertThat(measureAll(registry, "foo_total_duration_seconds", "method", "POST")
                           .get("sum")).isGreaterThan(0);
    }

    @Test
    public void rpc() {
        final MeterRegistry registry = new PrometheusMeterRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/bar", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/bar"));

        final MeterIdFunction meterIdFunction = MeterIdFunction.ofDefault("bar");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C, "example.com");
        RequestMetricSupport.setup(ctx, meterIdFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/bar"));
        ctx.logBuilder().requestContent(new DefaultRpcRequest(Object.class, "baz"), null);

        assertThat(measure(registry, "bar_active_requests", "method", "baz")).isOne();
    }
}
