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
package com.linecorp.armeria.internal.common.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.measureAll;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeKey;

class RequestMetricSupportTest {

    private static final AttributeKey<Boolean> REQUEST_METRICS_SET =
            AttributeKey.valueOf(RequestMetricSupportTest.class, "REQUEST_METRICS_SET");

    @Test
    void httpSuccess() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        // FIXME(trustin): In reality, most HTTP requests will not have any name.
        //                 As a result, `activeRequestPrefix()` will be invoked only after
        //                 a request is completed, i.e. active request count will be inaccurate,
        //                 especially for streaming requests.
        ctx.logBuilder().name("POST");

        Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.active.requests#value{method=POST}", 1.0);

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().requestLength(123);
        ctx.logBuilder().endRequest();

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endResponse();

        measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.active.requests#value{method=POST}", 0.0)
                .containsEntry("foo.requests#count{http.status=200,method=POST,result=success}", 1.0)
                .containsEntry("foo.requests#count{http.status=200,method=POST,result=failure}", 0.0)
                .containsEntry("foo.connection.acquisition.duration#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.dns.resolution.duration#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.socket.connect.duration#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.pending.acquisition.duration#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.request.length#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.request.length#total{http.status=200,method=POST}", 123.0)
                .containsEntry("foo.response.duration#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.response.length#count{http.status=200,method=POST}", 1.0)
                .containsEntry("foo.response.length#total{http.status=200,method=POST}", 456.0)
                .containsEntry("foo.total.duration#count{http.status=200,method=POST}", 1.0)
                // This metric is inserted only when RetryingClient is Used.
                .doesNotContainKey("foo.actual.requests#count{http.status=200,method=POST}");
    }

    private static ClientConnectionTimings newConnectionTimings() {
        return ClientConnectionTimings.builder()
                                      .dnsResolutionEnd()
                                      .socketConnectStart()
                                      .socketConnectEnd()
                                      .pendingAcquisitionStart()
                                      .pendingAcquisitionEnd()
                                      .build();
    }

    @Test
    void httpFailure() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(500));
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.active.requests#value{method=POST}", 0.0)
                .containsEntry("foo.requests#count{http.status=500,method=POST,result=success}", 0.0)
                .containsEntry("foo.requests#count{http.status=500,method=POST,result=failure}", 1.0)
                .containsEntry("foo.response.duration#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.response.length#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.total.duration#count{http.status=500,method=POST}", 1.0);
    }

    @Test
    void actualRequestsIncreasedWhenRetrying() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        addLogInfoInDerivedCtx(ctx);

        Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements).containsEntry("foo.active.requests#value{method=POST}", 1.0);

        addLogInfoInDerivedCtx(ctx);
        // Does not increase the active requests.
        assertThat(measurements).containsEntry("foo.active.requests#value{method=POST}", 1.0);

        ctx.logBuilder().endResponseWithLastChild();

        measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.active.requests#value{method=POST}", 0.0)
                .containsEntry("foo.requests#count{http.status=500,method=POST,result=success}", 0.0)
                .containsEntry("foo.requests#count{http.status=500,method=POST,result=failure}", 1.0)
                .containsEntry("foo.actual.requests#count{http.status=500,method=POST}", 2.0)
                .containsEntry("foo.connection.acquisition.duration#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.dns.resolution.duration#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.socket.connect.duration#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.pending.acquisition.duration#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.request.length#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.request.length#total{http.status=500,method=POST}", 123.0)
                .containsEntry("foo.response.duration#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.response.length#count{http.status=500,method=POST}", 1.0)
                .containsEntry("foo.response.length#total{http.status=500,method=POST}", 456.0)
                .containsEntry("foo.total.duration#count{http.status=500,method=POST}", 1.0);
    }

    @Test
    void responseTimedOutInClientSide() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse(ResponseTimeoutException.get());

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.active.requests#value{method=POST}", 0.0)
                .containsEntry("foo.requests#count{http.status=0,method=POST,result=success}", 0.0)
                .containsEntry("foo.requests#count{http.status=0,method=POST,result=failure}", 1.0)
                .containsEntry("foo.timeouts#count{cause=WriteTimeoutException,http.status=0,method=POST}", 0.0)
                .containsEntry("foo.timeouts#count{cause=ResponseTimeoutException," +
                               "http.status=0,method=POST}", 1.0)
                .containsEntry("foo.response.duration#count{http.status=0,method=POST}", 1.0)
                .containsEntry("foo.response.length#count{http.status=0,method=POST}", 1.0)
                .containsEntry("foo.total.duration#count{http.status=0,method=POST}", 1.0);
    }

    @Test
    void writeTimedOutInClientSide() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx = setupClientRequestCtx(registry);

        ctx.logBuilder().endRequest(WriteTimeoutException.get());
        ctx.logBuilder().endResponse(WriteTimeoutException.get());

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.active.requests#value{method=POST}", 0.0)
                .containsEntry("foo.requests#count{http.status=0,method=POST,result=success}", 0.0)
                .containsEntry("foo.requests#count{http.status=0,method=POST,result=failure}", 1.0)
                .containsEntry("foo.timeouts#count{cause=WriteTimeoutException,http.status=0,method=POST}", 1.0)
                .containsEntry("foo.timeouts#count{cause=ResponseTimeoutException," +
                               "http.status=0,method=POST}", 0.0)
                .containsEntry("foo.response.duration#count{http.status=0,method=POST}", 0.0)
                .containsEntry("foo.response.length#count{http.status=0,method=POST}", 0.0)
                .containsEntry("foo.total.duration#count{http.status=0,method=POST}", 0.0);
    }

    private static ClientRequestContext setupClientRequestCtx(MeterRegistry registry) {
        final ClientRequestContext ctx =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/foo"))
                                    .meterRegistry(registry)
                                    .endpoint(Endpoint.of("example.com", 8080))
                                    .connectionTimings(newConnectionTimings())
                                    .build();

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");
        RequestMetricSupport.setup(ctx, REQUEST_METRICS_SET, meterIdPrefixFunction, false);
        return ctx;
    }

    private static void addLogInfoInDerivedCtx(ClientRequestContext ctx) {
        final ClientRequestContext derivedCtx =
                ctx.newDerivedContext(ctx.id(), ctx.request(), ctx.rpcRequest());

        ctx.logBuilder().addChild(derivedCtx.log());
        derivedCtx.logBuilder().session(null, ctx.sessionProtocol(), newConnectionTimings());
        derivedCtx.logBuilder().requestFirstBytesTransferred();
        derivedCtx.logBuilder().requestContent(null, null);
        derivedCtx.logBuilder().requestLength(123);

        derivedCtx.logBuilder().responseHeaders(ResponseHeaders.of(500));
        derivedCtx.logBuilder().responseFirstBytesTransferred();
        derivedCtx.logBuilder().responseLength(456);
        derivedCtx.logBuilder().endRequest();
        derivedCtx.logBuilder().endResponse();
    }

    @Test
    void requestTimedOutInServerSide() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/foo"))
                                     .meterRegistry(registry)
                                     .build();

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("foo");
        RequestMetricSupport.setup(ctx, REQUEST_METRICS_SET, meterIdPrefixFunction, true);

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(503)); // 503 when request timed out
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseLength(456);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse(RequestTimeoutException.get());

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements)
                .containsEntry("foo.active.requests#value{hostname.pattern=*,method=POST," +
                               "route=exact:/foo}", 0.0)
                .containsEntry("foo.requests#count{hostname.pattern=*,http.status=503,method=POST," +
                               "result=success,route=exact:/foo}", 0.0)
                .containsEntry("foo.requests#count{hostname.pattern=*,http.status=503,method=POST," +
                               "result=failure,route=exact:/foo}", 1.0)
                .containsEntry("foo.timeouts#count{cause=RequestTimeoutException,hostname.pattern=*," +
                               "http.status=503,method=POST,route=exact:/foo}", 1.0)
                .containsEntry("foo.response.duration#count{hostname.pattern=*,http.status=503,method=POST," +
                               "route=exact:/foo}", 1.0)
                .containsEntry("foo.response.length#count{hostname.pattern=*,http.status=503,method=POST," +
                               "route=exact:/foo}", 1.0)
                .containsEntry("foo.total.duration#count{hostname.pattern=*,http.status=503,method=POST," +
                               "route=exact:/foo}", 1.0);
    }

    @Test
    void rpc() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ClientRequestContext ctx =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/bar"))
                                    .meterRegistry(registry)
                                    .endpoint(Endpoint.of("example.com", 8080))
                                    .build();

        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("bar");
        RequestMetricSupport.setup(ctx, REQUEST_METRICS_SET, meterIdPrefixFunction, false);

        ctx.logBuilder().name("baz");

        assertThat(measureAll(registry)).containsEntry("bar.active.requests#value{method=baz}", 1.0);
    }

    @Test
    void serviceAndClientContext() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/foo"))
                                     .meterRegistry(registry)
                                     .build();

        RequestMetricSupport.setup(sctx, REQUEST_METRICS_SET, MeterIdPrefixFunction.ofDefault("foo"), true);
        sctx.logBuilder().endRequest();
        try (SafeCloseable ignored = sctx.push()) {
            final ClientRequestContext cctx =
                    ClientRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/foo"))
                                        .meterRegistry(registry)
                                        .endpoint(Endpoint.of("example.com", 8080))
                                        .build();
            RequestMetricSupport.setup(cctx, AttributeKey.valueOf("differentKey"),
                                       MeterIdPrefixFunction.ofDefault("bar"), false);
            cctx.logBuilder().endRequest();
            cctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
            cctx.logBuilder().endResponse();
        }
        sctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        sctx.logBuilder().endResponse();

        final Map<String, Double> measurements = measureAll(registry);
        assertThat(measurements)
                // clientRequestContext
                .containsEntry("bar.active.requests#value{method=POST}", 0.0)
                .containsEntry("bar.requests#count{http.status=200,method=POST,result=success}", 1.0)
                .containsEntry("bar.requests#count{http.status=200,method=POST,result=failure}", 0.0)
                .containsEntry("bar.response.duration#count{http.status=200,method=POST}", 1.0)
                .containsEntry("bar.response.length#count{http.status=200,method=POST}", 1.0)
                .containsEntry("bar.total.duration#count{http.status=200,method=POST}", 1.0)
                // serviceRequestContext
                .containsEntry("foo.active.requests#value{hostname.pattern=*,method=POST," +
                               "route=exact:/foo}", 0.0)
                .containsEntry("foo.requests#count{hostname.pattern=*,http.status=200,method=POST," +
                               "result=success,route=exact:/foo}", 1.0)
                .containsEntry("foo.requests#count{hostname.pattern=*,http.status=200,method=POST," +
                               "result=failure,route=exact:/foo}", 0.0)
                .containsEntry("foo.response.duration#count{hostname.pattern=*,http.status=200,method=POST," +
                               "route=exact:/foo}", 1.0)
                .containsEntry("foo.response.length#count{hostname.pattern=*,http.status=200,method=POST," +
                               "route=exact:/foo}", 1.0)
                .containsEntry("foo.total.duration#count{hostname.pattern=*,http.status=200,method=POST," +
                               "route=exact:/foo}", 1.0);
    }
}
