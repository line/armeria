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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import reactor.test.StepVerifier;

class HttpResponseWrapperTest {

    @Test
    void headersAndData() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DecodedHttpResponse res =
                new DecodedHttpResponse(eventLoop, new DefaultStreamMessage<>());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()))).isTrue();
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        wrapper.close();

        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3))
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void headersAndTrailers() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DecodedHttpResponse res =
                new DecodedHttpResponse(eventLoop, new DefaultStreamMessage<>());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(200))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        wrapper.close();

        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(200))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void dataIsIgnoreAfterSecondHeaders() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DecodedHttpResponse res =
                new DecodedHttpResponse(eventLoop, new DefaultStreamMessage<>());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(200))).isTrue();
        assertThat(wrapper.tryWrite(
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue(); // Second header is trailers.
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isFalse();
        wrapper.close();

        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(200))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void splitTrailersIsIgnored() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DecodedHttpResponse res =
                new DecodedHttpResponse(eventLoop, new DefaultStreamMessage<>());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(200))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        wrapper.close();

        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(200))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void splitTrailersAfterDataIsIgnored() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DecodedHttpResponse res =
                new DecodedHttpResponse(eventLoop, new DefaultStreamMessage<>());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()))).isTrue();
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        wrapper.close();

        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3))
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void informationalHeadersHeadersDataAndTrailers() throws Exception {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DecodedHttpResponse res =
                new DecodedHttpResponse(eventLoop, new DefaultStreamMessage<>());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(100))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("a"), "b"))).isTrue();
        assertThat(wrapper.tryWrite(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()))).isTrue();
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        wrapper.close();

        StepVerifier.create(res)
                    .expectNext(ResponseHeaders.of(100))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("a"), "b"))
                    .expectNext(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3))
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectNext(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))
                    .expectComplete()
                    .verify();
    }

    private static HttpResponseWrapper httpResponseWrapper(DecodedHttpResponseWriter res) {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext cctx = ClientRequestContext.builder(req).build();
        final InboundTrafficController controller = InboundTrafficController.disabled();
        final Channel channel = cctx.log().ensureAvailable(RequestLogProperty.SESSION).channel();
        assertThat(channel).isNotNull();
        final TestHttpResponseDecoder decoder = new TestHttpResponseDecoder(channel, controller);

        res.init(controller);
        return decoder.addResponse(1, res, cctx, cctx.eventLoop(), cctx.responseTimeoutMillis(),
                                   cctx.maxResponseLength());
    }

    private static class TestHttpResponseDecoder extends HttpResponseDecoder {
        TestHttpResponseDecoder(Channel channel, InboundTrafficController inboundTrafficController) {
            super(channel, inboundTrafficController);
        }

        @Override
        KeepAliveHandler keepAliveHandler() {
            return NoopKeepAliveHandler.INSTANCE;
        }
    }
}
