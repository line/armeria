/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_MD5;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryStrategy;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class DefaultHttpRequestDuplicatorTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/long_streaming", (ctx, req) -> {
                final HttpResponseWriter response = HttpResponse.streaming();
                response.write(ResponseHeaders.of(200));
                req.aggregate().handle((aggregatedReq, cause) -> {
                    response.write(HttpData.ofUtf8("Hello"));
                    // Close response after receiving all requests
                    response.close();
                    return null;
                });
                return response;
            });
        }
    };

    @Test
    void aggregateTwice() {
        final AggregatedHttpRequest aReq = AggregatedHttpRequest.of(
                HttpMethod.PUT, "/foo", PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));

        final HttpRequest publisher = aReq.toHttpRequest();
        final HttpRequestDuplicator reqDuplicator = publisher.toDuplicator();

        final AggregatedHttpRequest req1 = reqDuplicator.duplicate().aggregate().join();
        final AggregatedHttpRequest req2 = reqDuplicator.duplicate().aggregate().join();

        assertThat(req1.headers()).isEqualTo(
                RequestHeaders.of(HttpMethod.PUT, "/foo",
                                  HttpHeaderNames.CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                  CONTENT_LENGTH, 3));
        assertThat(req1.content()).isEqualTo(HttpData.of(StandardCharsets.UTF_8, "bar"));
        assertThat(req1.trailers()).isEqualTo(
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));

        assertThat(req2.headers()).isEqualTo(
                RequestHeaders.of(HttpMethod.PUT, "/foo",
                                  HttpHeaderNames.CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                  CONTENT_LENGTH, 3));
        assertThat(req2.content()).isEqualTo(HttpData.of(StandardCharsets.UTF_8, "bar"));
        assertThat(req2.trailers()).isEqualTo(
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
        reqDuplicator.close();
    }

    @Test
    void longLivedRequest() {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(RetryingClient.newDecorator(
                                 RetryStrategy.onServerErrorStatus(Backoff.withoutDelay())))
                         .build();

        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, "/long_streaming");
        writeStreamingRequest(req, 0);
        final AggregatedHttpResponse res = client.execute(req).aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Hello");
    }

    private static void writeStreamingRequest(HttpRequestWriter req, int index) {
        if (index == 10) {
            req.close();
            return;
        }
        req.write(HttpData.ofUtf8(String.valueOf(index)));
        req.whenConsumed().thenRun(() -> eventLoop.get().schedule(() -> writeStreamingRequest(req, index + 1),
                                                                  300, TimeUnit.MILLISECONDS));
    }
}
