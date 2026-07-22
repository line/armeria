/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ReproducibleHttpRequestRetryTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/upload", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> AggregatedHttpResponse.of(
                            HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                            agg.contentUtf8()).toHttpResponse())));
        }
    };

    /**
     * Regression test for the retry-after-mid-body-failure bug. The body supplier faults the FIRST
     * attempt's request body (as if a mid-body transport failure occurred) and produces a good body
     * on every subsequent attempt. A correct implementation regenerates the body for the retry and
     * succeeds; the buggy implementation (where attempt #1 uses the caller's request as the live wire
     * stream) faults the template request's {@code whenComplete()} and skips the retry entirely.
     */
    @Test
    void retriesAfterFirstAttemptBodyFailsMidStream() {
        final AtomicInteger bodyCalls = new AtomicInteger();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/upload",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        final Supplier<StreamMessage<? extends HttpObject>> bodySupplier = () -> {
            final int call = bodyCalls.incrementAndGet();
            if (call == 1) {
                // Emit one chunk, then fault the request body mid-stream.
                return StreamMessage.concat(
                        StreamMessage.of(HttpData.ofUtf8("partial")),
                        StreamMessage.aborted(new RuntimeException("mid-body failure")));
            }
            return StreamMessage.of(HttpData.ofUtf8("hello-body"));
        };

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(RetryingClient.newDecorator(
                                 RetryRule.builder()
                                          .onException()
                                          .onServerErrorStatus()
                                          .thenBackoff()))
                         .build();

        final RequestOptions options =
                RequestOptions.builder()
                              .exchangeType(ExchangeType.REQUEST_STREAMING)
                              .build();

        final HttpRequest req = HttpRequest.reproducible(headers, bodySupplier);
        final AggregatedHttpResponse res = client.execute(req, options).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello-body");
        // Body regenerated at least twice: the faulted first attempt and the successful retry.
        assertThat(bodyCalls).hasValueGreaterThanOrEqualTo(2);
    }
}
