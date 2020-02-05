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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.Channel;

class HttpResponseWrapperTest {

    @Test
    void headersAndData() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()))).isTrue();
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        wrapper.close();

        final List<HttpObject> drained = res.drainAll().join();
        assertThat(drained).containsExactly(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3),
                HttpData.ofUtf8("foo"));
    }

    @Test
    void headersAndTrailers() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(200))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        wrapper.close();

        final List<HttpObject> drained = res.drainAll().join();
        assertThat(drained).containsExactly(
                ResponseHeaders.of(200),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    void dataIsIgnoreAfterSecondHeaders() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(200))).isTrue();
        assertThat(wrapper.tryWrite(
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue(); // Second header is trailers.
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isFalse();
        wrapper.close();

        final List<HttpObject> drained = res.drainAll().join();
        assertThat(drained).containsExactly(
                ResponseHeaders.of(200),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    void splitTrailersIsIgnored() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(200))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        wrapper.close();

        final List<HttpObject> drained = res.drainAll().join();
        assertThat(drained).containsExactly(
                ResponseHeaders.of(200),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    void splitTrailersAfterDataIsIgnored() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()))).isTrue();
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"))).isFalse();
        wrapper.close();

        final List<HttpObject> drained = res.drainAll().join();
        assertThat(drained).containsExactly(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 3),
                HttpData.ofUtf8("foo"),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    void informationalHeadersHeadersDataAndTrailers() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        assertThat(wrapper.tryWrite(ResponseHeaders.of(100))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("a"), "b"))).isTrue();
        assertThat(wrapper.tryWrite(
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()))).isTrue();
        assertThat(wrapper.tryWrite(HttpData.ofUtf8("foo"))).isTrue();
        assertThat(wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"))).isTrue();
        wrapper.close();

        final List<HttpObject> drained = res.drainAll().join();
        assertThat(drained).containsExactly(
                ResponseHeaders.of(100),
                HttpHeaders.of(HttpHeaderNames.of("a"), "b"),
                ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, "foo".length()),
                HttpData.ofUtf8("foo"),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    private static HttpResponseWrapper httpResponseWrapper(DecodedHttpResponse res) {
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
    }
}
