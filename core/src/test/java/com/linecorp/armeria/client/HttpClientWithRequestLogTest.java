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

import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.testing.internal.AnticipatedException;

public class HttpClientWithRequestLogTest {

    private static final String LOCAL_HOST = "http://127.0.0.1/";

    private static final AtomicReference<Throwable> requestCauseHolder = new AtomicReference<>();
    private static final AtomicReference<Throwable> responseCauseHolder = new AtomicReference<>();

    @Before
    public void setUp() {
        requestCauseHolder.set(null);
        responseCauseHolder.set(null);
    }

    @Test
    public void exceptionRaisedInDecorator() {
        final HttpClient client = new HttpClientBuilder(LOCAL_HOST)
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
    public void invalidPath() {
        final HttpClient client = new HttpClientBuilder(LOCAL_HOST)
                .decorator((delegate, ctx, req) -> {
                    req.headers().path("/%");
                    return delegate.execute(ctx, req);
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
    public void unresolvedUri() {
        final HttpClient client = new HttpClientBuilder("http://unresolved.armeria.com").decorator(
                new ExceptionHoldingDecorator()).build();
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        assertThatThrownBy(() -> client.execute(req).aggregate().get()).isInstanceOf(Exception.class);

        await().untilAsserted(() -> assertThat(requestCauseHolder.get()).isNotNull());
        await().untilAsserted(() -> assertThat(responseCauseHolder.get()).isNotNull());
        await().untilAsserted(() -> assertThat(req.isComplete()).isTrue());
    }

    @Test
    public void connectionError() {
        // According to rfc7805, TCP port number 1 is not used so a connection error always happens.
        final HttpClient client = new HttpClientBuilder("http://127.0.0.1:1")
                .decorator(new ExceptionHoldingDecorator()).build();
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        assertThatThrownBy(() -> client.execute(req).aggregate().get())
                .hasCauseInstanceOf(ConnectException.class);

        await().untilAsserted(() -> assertThat(
                requestCauseHolder.get()).hasCauseInstanceOf(ConnectException.class));
        await().untilAsserted(() -> assertThat(
                responseCauseHolder.get()).hasCauseInstanceOf(ConnectException.class));
        await().untilAsserted(() -> assertThat(req.isComplete()).isTrue());
    }

    private static class ExceptionHoldingDecorator
            implements DecoratingClientFunction<HttpRequest, HttpResponse> {

        @Override
        public HttpResponse execute(Client<HttpRequest, HttpResponse> delegate, ClientRequestContext ctx,
                                    HttpRequest req) throws Exception {
            final RequestLog requestLog = ctx.log();
            requestLog.addListener(log -> requestCauseHolder.set(log.requestCause()),
                                   RequestLogAvailability.REQUEST_END);
            requestLog.addListener(log -> responseCauseHolder.set(log.responseCause()),
                                   RequestLogAvailability.RESPONSE_END);
            return delegate.execute(ctx, req);
        }
    }
}
