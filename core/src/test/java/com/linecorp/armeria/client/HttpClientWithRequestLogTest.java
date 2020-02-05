/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.testing.AnticipatedException;

class HttpClientWithRequestLogTest {

    private static final String LOCAL_HOST = "http://127.0.0.1/";

    private static final AtomicReference<Throwable> requestCauseHolder = new AtomicReference<>();
    private static final AtomicReference<Throwable> responseCauseHolder = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        requestCauseHolder.set(null);
        responseCauseHolder.set(null);
    }

    @Test
    void exceptionRaisedInDecorator() {
        final WebClient client =
                WebClient.builder(LOCAL_HOST)
                         .decorator((delegate, ctx, req1) -> {
                             throw new AnticipatedException();
                         })
                         .decorator(new ExceptionHoldingDecorator())
                         .build();

        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        assertThatThrownBy(() -> client.execute(req).aggregate().get())
                .hasCauseExactlyInstanceOf(AnticipatedException.class);

        // If the RequestLog has requestCause and responseCause, the RequestLog is complete.
        // The RequestLog should be complete so that ReleasableHolder#release() is called in UserClient
        // to decrease the active request count of EventLoop.
        await().untilAsserted(() -> assertThat(
                requestCauseHolder.get()).isExactlyInstanceOf(AnticipatedException.class));
        await().untilAsserted(() -> assertThat(
                responseCauseHolder.get()).isExactlyInstanceOf(AnticipatedException.class));
        await().untilAsserted(() -> assertThat(req.isComplete()).isTrue());
    }

    @Test
    void invalidPath() {
        final WebClient client =
                WebClient.builder(LOCAL_HOST)
                         .decorator((delegate, ctx, req) -> {
                             final HttpRequest badReq = req.withHeaders(req.headers().toBuilder().path("/%"));
                             return delegate.execute(ctx, badReq);
                         })
                         .decorator(new ExceptionHoldingDecorator())
                         .build();

        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        assertThatThrownBy(() -> client.execute(req).aggregate().get())
                .hasCauseExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid path");

        await().untilAsserted(() -> assertThat(
                requestCauseHolder.get()).isExactlyInstanceOf(IllegalArgumentException.class));
        await().untilAsserted(() -> assertThat(
                responseCauseHolder.get()).isExactlyInstanceOf(IllegalArgumentException.class));
        await().untilAsserted(() -> assertThat(req.isComplete()).isTrue());
    }

    @Test
    void unresolvedUri() {
        final AtomicReference<ClientConnectionTimings> ref = new AtomicReference<>();

        final WebClient client =
                WebClient.builder("http://unresolved.armeria.com")
                         .decorator(new ExceptionHoldingDecorator())
                         .decorator((delegate, ctx, req) -> {
                             ctx.log().whenAvailable(RequestLogProperty.SESSION)
                                .thenAccept(log -> ref.set(log.connectionTimings()));
                             return delegate.execute(ctx, req);
                         })
                         .build();

        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        assertThatThrownBy(() -> client.execute(req).aggregate().get()).isInstanceOf(Exception.class);

        await().untilAsserted(() -> assertThat(ref.get()).isNotNull());
        final ClientConnectionTimings timings = ref.get();
        assertThat(timings.connectionAcquisitionStartTimeMicros()).isPositive();

        final long dnsResolutionDurationNanos = timings.dnsResolutionDurationNanos();
        assertThat(dnsResolutionDurationNanos).isPositive();
        assertThat(timings.connectionAcquisitionDurationNanos())
                .isGreaterThanOrEqualTo(dnsResolutionDurationNanos);

        await().untilAsserted(() -> assertThat(requestCauseHolder.get()).isNotNull());
        await().untilAsserted(() -> assertThat(responseCauseHolder.get()).isNotNull());
        await().untilAsserted(() -> assertThat(req.isComplete()).isTrue());
    }

    @Test
    void connectionError() {
        final AtomicReference<ClientConnectionTimings> ref = new AtomicReference<>();

        // According to rfc7805, TCP port number 1 is not used so a connection error always happens.
        final WebClient client =
                WebClient.builder("http://127.0.0.1:1")
                         .decorator(new ExceptionHoldingDecorator())
                         .decorator((delegate, ctx, req) -> {
                             ctx.log().whenAvailable(RequestLogProperty.SESSION)
                                .thenAccept(log -> ref.set(log.connectionTimings()));
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        assertThatThrownBy(() -> client.execute(req).aggregate().get())
                .hasCauseInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(ConnectException.class);

        await().untilAsserted(() -> assertThat(ref.get()).isNotNull());
        final ClientConnectionTimings timings = ref.get();
        assertThat(timings.connectionAcquisitionStartTimeMicros()).isPositive();

        final long connectDurationNanos = timings.socketConnectDurationNanos();
        assertThat(connectDurationNanos).isPositive();
        assertThat(timings.connectionAcquisitionDurationNanos()).isGreaterThanOrEqualTo(connectDurationNanos);

        await().untilAsserted(() -> assertThat(
                requestCauseHolder.get()).hasCauseInstanceOf(ConnectException.class));
        await().untilAsserted(() -> assertThat(
                responseCauseHolder.get()).hasCauseInstanceOf(ConnectException.class));
        await().untilAsserted(() -> assertThat(req.isComplete()).isTrue());
    }

    private static class ExceptionHoldingDecorator implements DecoratingHttpClientFunction {

        @Override
        public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx,
                                    HttpRequest req) throws Exception {
            ctx.log().whenRequestComplete().thenAccept(log -> requestCauseHolder.set(log.requestCause()));
            ctx.log().whenComplete().thenAccept(log -> responseCauseHolder.set(log.responseCause()));
            return delegate.execute(ctx, req);
        }
    }
}
