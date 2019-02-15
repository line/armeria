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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.DefaultRpcRequest;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;

public class RequestMetricSupportTest {

    @Test
    public void httpSuccess() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestFirstBytesTransferred();
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
                                .containsEntry("foo.requestLength#count{httpStatus=200,method=POST}", 1.0)
                                .containsEntry("foo.requestLength#total{httpStatus=200,method=POST}", 123.0)
                                .containsEntry("foo.responseDuration#count{httpStatus=200,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#count{httpStatus=200,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#total{httpStatus=200,method=POST}", 456.0)
                                .containsEntry("foo.totalDuration#count{httpStatus=200,method=POST}", 1.0)
                                // This metric is inserted only when RetryingClient is Used.
                                .doesNotContainKey("foo.actualRequests#count{httpStatus=200,method=POST}");
    }

    @Test
    public void httpFailure() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().responseHeaders(HttpHeaders.of(500));
        ctx.logBuilder().responseFirstBytesTransferred();
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
    public void actualRequestsIncreasedWhenRetrying() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        addLogInfoInDerivedCtx(ctx);

        Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 1.0);

        addLogInfoInDerivedCtx(ctx);
        // Does not increase the active requests.
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 1.0);

        ctx.logBuilder().endResponseWithLastChild();

        measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 0.0)
                                .containsEntry("foo.requests#count{httpStatus=500,method=POST,result=success}",
                                               0.0)
                                .containsEntry("foo.requests#count{httpStatus=500,method=POST,result=failure}",
                                               1.0)
                                .containsEntry("foo.actualRequests#count{httpStatus=500,method=POST}",
                                               2.0)
                                .containsEntry("foo.requestLength#count{httpStatus=500,method=POST}", 1.0)
                                .containsEntry("foo.requestLength#total{httpStatus=500,method=POST}", 123.0)
                                .containsEntry("foo.responseDuration#count{httpStatus=500,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#count{httpStatus=500,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#total{httpStatus=500,method=POST}", 456.0)
                                .containsEntry("foo.totalDuration#count{httpStatus=500,method=POST}", 1.0);
    }

    @Test
    public void responseTimedOutInClientSide() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse(ResponseTimeoutException.get());

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 0.0)
                                .containsEntry("foo.requests#count{httpStatus=0,method=POST,result=success}",
                                               0.0)
                                .containsEntry("foo.requests#count{httpStatus=0,method=POST,result=failure}",
                                               1.0)
                                .containsEntry("foo.timeouts#count{cause=WriteTimeoutException," +
                                               "httpStatus=0,method=POST}", 0.0)
                                .containsEntry("foo.timeouts#count{cause=ResponseTimeoutException," +
                                               "httpStatus=0,method=POST}", 1.0)
                                .containsEntry("foo.responseDuration#count{httpStatus=0,method=POST}", 1.0)
                                .containsEntry("foo.responseLength#count{httpStatus=0,method=POST}", 1.0)
                                .containsEntry("foo.totalDuration#count{httpStatus=0,method=POST}", 1.0);
    }

    @Test
    public void writeTimedOutInClientSide() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().endRequest(WriteTimeoutException.get());
        ctx.logBuilder().endResponse(WriteTimeoutException.get());

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.activeRequests#value{method=POST}", 0.0)
                                .containsEntry("foo.requests#count{httpStatus=0,method=POST,result=success}",
                                               0.0)
                                .containsEntry("foo.requests#count{httpStatus=0,method=POST,result=failure}",
                                               1.0)
                                .containsEntry("foo.timeouts#count{cause=WriteTimeoutException," +
                                               "httpStatus=0,method=POST}", 1.0)
                                .containsEntry("foo.timeouts#count{cause=ResponseTimeoutException," +
                                               "httpStatus=0,method=POST}", 0.0)
                                .containsEntry("foo.responseDuration#count{httpStatus=0,method=POST}", 0.0)
                                .containsEntry("foo.responseLength#count{httpStatus=0,method=POST}", 0.0)
                                .containsEntry("foo.totalDuration#count{httpStatus=0,method=POST}", 0.0);
    }

    private static ClientRequestContext setupClientRequestCtx(MeterRegistry registry) {
        final ClientRequestContext ctx =
                ClientRequestContextBuilder.of(HttpRequest.of(HttpMethod.POST, "/foo"))
                                           .meterRegistry(registry)
                                           .endpoint(Endpoint.of("example.com", 8080))
                                           .build();

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C);
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction, false);
        return ctx;
    }

    private static void addLogInfoInDerivedCtx(ClientRequestContext ctx) {
        final ClientRequestContext derivedCtx = ctx.newDerivedContext();
        ctx.logBuilder().addChild(derivedCtx.log());

        derivedCtx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        derivedCtx.logBuilder().requestFirstBytesTransferred();
        derivedCtx.logBuilder().requestContent(null, null);
        derivedCtx.logBuilder().requestLength(123);

        derivedCtx.logBuilder().responseHeaders(HttpHeaders.of(500));
        derivedCtx.logBuilder().responseFirstBytesTransferred();
        derivedCtx.logBuilder().responseLength(456);
        derivedCtx.logBuilder().endRequest();
        derivedCtx.logBuilder().endResponse();
    }

    @Test
    public void requestTimedOutInServerSide() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ServiceRequestContext ctx =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.POST, "/foo"))
                                            .meterRegistry(registry)
                                            .build();

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C);
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction, true);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/foo"));
        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().responseHeaders(HttpHeaders.of(503)); // 503 when request timed out
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse(RequestTimeoutException.get());

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.activeRequests#value{hostnamePattern=*,method=POST," +
                               "pathMapping=exact:/foo}", 0.0)
                .containsEntry("foo.requests#count{hostnamePattern=*,httpStatus=503,method=POST," +
                               "pathMapping=exact:/foo,result=success}", 0.0)
                .containsEntry("foo.requests#count{hostnamePattern=*,httpStatus=503,method=POST," +
                               "pathMapping=exact:/foo,result=failure}", 1.0)
                .containsEntry("foo.timeouts#count{cause=RequestTimeoutException,hostnamePattern=*," +
                               "httpStatus=503,method=POST,pathMapping=exact:/foo}", 1.0)
                .containsEntry("foo.responseDuration#count{hostnamePattern=*,httpStatus=503,method=POST," +
                               "pathMapping=exact:/foo}", 1.0)
                .containsEntry("foo.responseLength#count{hostnamePattern=*,httpStatus=503,method=POST," +
                               "pathMapping=exact:/foo}", 1.0)
                .containsEntry("foo.totalDuration#count{hostnamePattern=*,httpStatus=503,method=POST," +
                               "pathMapping=exact:/foo}", 1.0);
    }

    @Test
    public void rpc() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx =
                ClientRequestContextBuilder.of(HttpRequest.of(HttpMethod.POST, "/bar"))
                                           .meterRegistry(registry)
                                           .endpoint(Endpoint.of("example.com", 8080))
                                           .build();

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("bar");

        ctx.logBuilder().startRequest(mock(Channel.class), SessionProtocol.H2C);
        RequestMetricSupport.setup(ctx, meterIdPrefixFunction, false);

        ctx.logBuilder().requestHeaders(HttpHeaders.of(HttpMethod.POST, "/bar"));
        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().requestContent(new DefaultRpcRequest(Object.class, "baz"), null);

        assertThat(measureAll(registry)).containsEntry("bar.activeRequests#value{method=baz}", 1.0);
    }
}
