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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.Channel;

public class HttpResponseWrapperTest {

    @Test
    public void headersAndData() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        wrapper.tryWrite(HttpHeaders.of(200).addInt(HttpHeaderNames.CONTENT_LENGTH, "foo".length()));
        wrapper.tryWrite(HttpData.ofUtf8("foo"));
        wrapper.close();

        final List<HttpObject> unaggregated = unaggregate(res);
        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(200).addInt(HttpHeaderNames.CONTENT_LENGTH, 3),
                HttpData.ofUtf8("foo"));
    }

    @Test
    public void headersAndTrailers() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        wrapper.tryWrite(HttpHeaders.of(200));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
        wrapper.close();

        final List<HttpObject> unaggregated = unaggregate(res);
        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(200),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void dataIsIgnoreAfterSecondHeaders() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        wrapper.tryWrite(HttpHeaders.of(200));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz")); // Second header is trailers.
        wrapper.tryWrite(HttpData.ofUtf8("foo"));
        wrapper.close();

        final List<HttpObject> unaggregated = unaggregate(res);
        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(200),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void splitTrailersIsIgnored() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        wrapper.tryWrite(HttpHeaders.of(200));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"));
        wrapper.close();

        final List<HttpObject> unaggregated = unaggregate(res);
        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(200),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void splitTrailersAfterDataIsIgnored() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        wrapper.tryWrite(HttpHeaders.of(200).addInt(HttpHeaderNames.CONTENT_LENGTH, "foo".length()));
        wrapper.tryWrite(HttpData.ofUtf8("foo"));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("qux"), "quux"));
        wrapper.close();

        final List<HttpObject> unaggregated = unaggregate(res);
        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(200).addInt(HttpHeaderNames.CONTENT_LENGTH, 3),
                HttpData.ofUtf8("foo"),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    @Test
    public void infromationalHeadersHeadersDataAndTrailers() throws Exception {
        final DecodedHttpResponse res = new DecodedHttpResponse(CommonPools.workerGroup().next());
        final HttpResponseWrapper wrapper = httpResponseWrapper(res);

        wrapper.tryWrite(HttpHeaders.of(100));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("a"), "b"));
        wrapper.tryWrite(HttpHeaders.of(200).addInt(HttpHeaderNames.CONTENT_LENGTH, "foo".length()));
        wrapper.tryWrite(HttpData.ofUtf8("foo"));
        wrapper.tryWrite(HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
        wrapper.close();

        final List<HttpObject> unaggregated = unaggregate(res);
        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(100),
                HttpHeaders.of(HttpHeaderNames.of("a"), "b"),
                HttpHeaders.of(200).addInt(HttpHeaderNames.CONTENT_LENGTH, "foo".length()),
                HttpData.ofUtf8("foo"),
                HttpHeaders.of(HttpHeaderNames.of("bar"), "baz"));
    }

    private static HttpResponseWrapper httpResponseWrapper(DecodedHttpResponse res) {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext cctx = ClientRequestContextBuilder.of(req).build();
        final InboundTrafficController controller = InboundTrafficController.disabled();
        final TestHttpResponseDecoder decoder =
                new TestHttpResponseDecoder(cctx.log().channel(), controller);

        res.init(controller);
        return decoder.addResponse(1, req, res, cctx.logBuilder(), cctx.responseTimeoutMillis(),
                                   cctx.maxResponseLength());
    }

    private static class TestHttpResponseDecoder extends HttpResponseDecoder {
        TestHttpResponseDecoder(Channel channel,
                                InboundTrafficController inboundTrafficController) {
            super(channel, inboundTrafficController);
        }
    }

    private static List<HttpObject> unaggregate(StreamMessage<HttpObject> req) throws Exception {
        final List<HttpObject> unaggregatedd = new ArrayList<>();
        req.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                unaggregatedd.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        req.completionFuture().get(10, TimeUnit.SECONDS);
        return unaggregatedd;
    }
}
