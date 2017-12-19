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
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

public class RequestMetricSupportTest {

    @Test
    public void http() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/foo", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/foo"));

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C, "example.com");
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestContent(null, null);
        ctx.logBuilder().requestLength(123);

        assertThat(registry.find("foo.activeRequests")
                           .tags("method", "POST")
                           .value(Statistic.Count, 1).meter()).isPresent();
        assertThat(registry.find("foo.requestDuration")
                           .tags("method", "POST")
                           .value(Statistic.Count, 0).meter()).isPresent();
        assertThat(registry.find("foo.requestDuration")
                           .tags("method", "POST")
                           .value(Statistic.Total, 0).meter()).isPresent();
        assertThat(registry.find("foo.requestLength")
                           .tags("method", "POST")
                           .value(Statistic.Count, 0).meter()).isPresent();
        assertThat(registry.find("foo.requestLength")
                           .tags("method", "POST")
                           .value(Statistic.Total, 0).meter()).isPresent();

        ctx.logBuilder().endRequest();
        assertThat(registry.find("foo.requestDuration")
                           .tags("method", "POST")
                           .value(Statistic.Count, 1).meter()).isPresent();
        assertThat(registry.find("foo.requestLength")
                           .tags("method", "POST")
                           .value(Statistic.Count, 1).meter()).isPresent();
        assertThat(registry.find("foo.requestLength")
                           .tags("method", "POST")
                           .value(Statistic.Total, 123).meter()).isPresent();

        ctx.logBuilder().responseHeaders(HttpHeaders.of(200));
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endResponse();

        assertThat(registry.find("foo.activeRequests")
                           .tags("method", "POST")
                           .value(Statistic.Count, 0).meter()).isPresent();
        assertThat(registry.find("foo.requests")
                           .tags("method", "POST", "result", "success")
                           .value(Statistic.Count, 1).meter()).isPresent();
        assertThat(registry.find("foo.requests")
                           .tags("method", "POST", "result", "failure")
                           .value(Statistic.Count, 0).meter()).isPresent();
        assertThat(registry.find("foo.responseDuration")
                           .tags("method", "POST")
                           .value(Statistic.Count, 1).meter()).isPresent();
        assertThat(registry.find("foo.responseLength")
                           .tags("method", "POST")
                           .value(Statistic.Count, 1).meter()).isPresent();
        assertThat(registry.find("foo.responseLength")
                           .tags("method", "POST")
                           .value(Statistic.Total, 456).meter()).isPresent();
        assertThat(registry.find("foo.totalDuration")
                           .tags("method", "POST")
                           .value(Statistic.Count, 1).meter()).isPresent();
    }

    @Test
    public void rpc() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), registry, SessionProtocol.H2C,
                Endpoint.of("example.com", 8080), HttpMethod.POST, "/bar", null, null,
                ClientOptions.DEFAULT, HttpRequest.of(HttpMethod.POST, "/bar"));

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("bar");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C, "example.com");
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/bar"));
        ctx.logBuilder().requestContent(new DefaultRpcRequest(Object.class, "baz"), null);

        assertThat(registry.find("bar.activeRequests")
                           .tags("method", "baz")
                           .value(Statistic.Count, 1).meter()).isPresent();
    }
}
