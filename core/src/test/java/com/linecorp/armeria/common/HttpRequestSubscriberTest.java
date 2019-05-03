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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.FixedHttpRequest.EmptyFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.OneElementFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.RegularFixedHttpRequest;
import com.linecorp.armeria.common.FixedHttpRequest.TwoElementFixedHttpRequest;
import com.linecorp.armeria.common.stream.RegularFixedStreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

@RunWith(Parameterized.class)
public class HttpRequestSubscriberTest {

    private static final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/delayed_ok");

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

            // TODO(hyangtack) Remove one of the following services.
            // Returning a response immediately causes a failure of a test with PublisherBasedHttpRequest.
            sb.service("/ok", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/delayed_ok", (ctx, req) -> {
                           final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                           executor.schedule(() -> f.complete(HttpResponse.of(HttpStatus.OK)),
                                             100, TimeUnit.MILLISECONDS);
                           return HttpResponse.from(f);
                       }
            );
        }
    };

    static HttpClient client;

    @BeforeClass
    public static void beforeClass() {
        client = HttpClient.of(rule.httpUri("/"));
    }

    @Parameters(name = "{index}: request={0}")
    public static Collection<HttpRequest> parameters() {
        return ImmutableList.of(
                new EmptyFixedHttpRequest(headers),
                new OneElementFixedHttpRequest(
                        headers, HttpData.ofUtf8("body")),
                new TwoElementFixedHttpRequest(
                        headers, HttpData.ofUtf8("body1"), HttpData.ofUtf8("body2")),
                new RegularFixedHttpRequest(
                        headers, HttpData.ofUtf8("body1"), HttpData.ofUtf8("body2"), HttpData.ofUtf8("body3")),
                new PublisherBasedHttpRequest(
                        headers,
                        new HttpDataPublisher(new HttpData[] {
                                HttpData.ofUtf8("body1"),
                                HttpData.ofUtf8("body2"),
                                HttpData.ofUtf8("body3")
                        }))
        );
    }

    private final HttpRequest request;

    public HttpRequestSubscriberTest(HttpRequest request) {
        this.request = request;
    }

    @Test
    public void shouldCompleteFutureWithoutCause() throws Exception {
        final AggregatedHttpMessage response = client.execute(request).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        final CompletableFuture<Void> f = request.completionFuture();
        assertThat(f.isDone()).isTrue();
        f.get();
        assertThat(f.isCompletedExceptionally()).isFalse();
        assertThat(f.isCancelled()).isFalse();
    }

    private static final class HttpDataPublisher extends RegularFixedStreamMessage<HttpData> {
        private HttpDataPublisher(HttpData[] objs) {
            super(objs);
        }
    }
}
