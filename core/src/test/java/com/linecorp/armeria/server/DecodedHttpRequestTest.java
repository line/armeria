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

import java.util.List;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.InboundTrafficController;
import com.linecorp.armeria.testing.junit4.common.EventLoopRule;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

public class DecodedHttpRequestTest {

    @ClassRule
    public static final EventLoopRule eventLoop = new EventLoopRule();

    @Test
    public void dataOnly() throws Exception {
        final DecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        req.close();

        final List<HttpObject> drained = req.drainAll().join();
        assertThat(drained).containsExactly(HttpData.ofUtf8("foo"));
    }

    @Test
    public void trailersOnly() throws Exception {
        final DecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        req.close();

        final List<HttpObject> drained = req.drainAll().join();
        assertThat(drained).containsExactly(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void dataIsIgnoreAfterTrailers() throws Exception {
        final DecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(req.tryWrite(HttpData.ofUtf8("foo"))).isFalse();
        req.close();

        final List<HttpObject> drained = req.drainAll().join();
        assertThat(drained).containsExactly(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void splitTrailersIsIgnored() throws Exception {
        final DecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        req.close();

        final List<HttpObject> drained = req.drainAll().join();
        assertThat(drained).containsExactly(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void splitTrailersAfterDataIsIgnored() throws Exception {
        final DecodedHttpRequest req = decodedHttpRequest();
        assertThat(req.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(req.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        req.close();

        final List<HttpObject> drained = req.drainAll().join();
        assertThat(drained).containsExactly(HttpData.ofUtf8("foo"),
                                            HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void contentPreview() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         MediaType.PLAIN_TEXT_UTF_8);
        final ServiceRequestContext sctx =
                ServiceRequestContext.builder(HttpRequest.of(headers))
                                     .serverConfigurator(sb -> sb.contentPreview(100))
                                     .build();
        final DecodedHttpRequest req = decodedHttpRequest(headers, sctx);
        req.whenComplete().handle((ret, cause) -> {
            sctx.logBuilder().endRequest();
            return null;
        });

        req.subscribe(new ImmediateReleaseSubscriber());

        assertThat(req.tryWrite(new ByteBufHttpData(newBuffer("hello"), false))).isTrue();
        req.close();

        assertThat(sctx.log().whenRequestComplete().join().requestContentPreview()).isEqualTo("hello");
    }

    private static DecodedHttpRequest decodedHttpRequest() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final ServiceRequestContext sctx = ServiceRequestContext.of(HttpRequest.of(headers));
        return decodedHttpRequest(headers, sctx);
    }

    private static DecodedHttpRequest decodedHttpRequest(RequestHeaders headers, ServiceRequestContext sctx) {
        final DecodedHttpRequest request = new DecodedHttpRequest(sctx.eventLoop(), 1, 1, headers, true,
                                                                  InboundTrafficController.disabled(),
                                                                  sctx.maxRequestLength());
        request.init(sctx);
        return request;
    }

    private static ByteBuf newBuffer(String content) {
        return ByteBufAllocator.DEFAULT.buffer().writeBytes(content.getBytes());
    }

    private static class ImmediateReleaseSubscriber implements Subscriber<HttpObject> {

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Integer.MAX_VALUE);
        }

        @Override
        public void onNext(HttpObject obj) {
            ReferenceCountUtil.safeRelease(obj);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
