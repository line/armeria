/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import reactor.test.StepVerifier;

class StreamingDecodedHttpRequestTest {

    @Test
    void dataOnly() throws Exception {
        final StreamingDecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        req.close();

        StepVerifier.create(req)
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void trailersOnly() throws Exception {
        final StreamingDecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        req.close();

        StepVerifier.create(req)
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void dataIsIgnoreAfterTrailers() throws Exception {
        final StreamingDecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(req.tryWrite(HttpData.ofUtf8("foo"))).isFalse();
        req.close();

        StepVerifier.create(req)
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void splitTrailersIsIgnored() throws Exception {
        final StreamingDecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        req.close();

        StepVerifier.create(req)
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void splitTrailersAfterDataIsIgnored() throws Exception {
        final StreamingDecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        req.close();

        StepVerifier.create(req)
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    private static StreamingDecodedHttpRequest decodedHttpRequest() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final ServiceRequestContext sctx = ServiceRequestContext.of(HttpRequest.of(headers));
        return decodedHttpRequest(headers, sctx);
    }

    private static StreamingDecodedHttpRequest decodedHttpRequest(RequestHeaders headers,
                                                                  ServiceRequestContext sctx) {

        final StreamingDecodedHttpRequest
                request = new StreamingDecodedHttpRequest(sctx.eventLoop(), 1, 1, headers, true,
                                                          InboundTrafficController.disabled(),
                                                          sctx.maxRequestLength(), sctx.routingContext(),
                                                          ExchangeType.BIDI_STREAMING, 0, 0, false, false);
        request.init(sctx);
        return request;
    }
}
