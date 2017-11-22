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
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.StreamMessage;

public class DefaultAggregatedHttpMessageTest {

    @Test
    public void toHttpRequest() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.POST, "/foo", PLAIN_TEXT_UTF_8, "bar");
        final HttpRequest req = aReq.toHttpRequest();
        final List<HttpObject> unaggregated = unaggregate(req);

        assertThat(req.headers()).isEqualTo(HttpHeaders.of(HttpMethod.POST, "/foo")
                                                       .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                                                       .setInt(CONTENT_LENGTH, 3));
        assertThat(unaggregated).containsExactly(HttpData.of(StandardCharsets.UTF_8, "bar"));
    }

    @Test
    public void toHttpRequestWithoutContent() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(HttpMethod.GET, "/bar");
        final HttpRequest req = aReq.toHttpRequest();
        final List<HttpObject> unaggregated = unaggregate(req);

        assertThat(req.headers()).isEqualTo(HttpHeaders.of(HttpMethod.GET, "/bar"));
        assertThat(unaggregated).isEmpty();
    }

    @Test
    public void toHttpRequestWithTrailingHeaders() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.PUT, "/baz", PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
        final HttpRequest req = aReq.toHttpRequest();
        final List<HttpObject> unaggregated = unaggregate(req);

        assertThat(req.headers()).isEqualTo(HttpHeaders.of(HttpMethod.PUT, "/baz")
                                                       .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                                                       .setInt(CONTENT_LENGTH, 3));
        assertThat(unaggregated).containsExactly(
                HttpData.of(StandardCharsets.UTF_8, "bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
    }

    @Test
    public void toHttpRequestAgainstResponse() {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(200);
        assertThatThrownBy(aRes::toHttpRequest).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void toHttpRequestWithoutPath() {
        // Method only
        assertThatThrownBy(() -> AggregatedHttpMessage.of(HttpHeaders.of(HttpHeaderNames.METHOD, "GET"))
                                                      .toHttpRequest())
                .isInstanceOf(IllegalStateException.class);

        // Path only
        assertThatThrownBy(() -> AggregatedHttpMessage.of(HttpHeaders.of(HttpHeaderNames.PATH, "/charlie"))
                                                      .toHttpRequest())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void toHttpResponse() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(
                HttpStatus.OK, PLAIN_TEXT_UTF_8, "alice");
        final HttpResponse res = aRes.toHttpResponse();
        final List<HttpObject> unaggregated = unaggregate(res);

        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(HttpStatus.OK)
                           .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                           .setInt(CONTENT_LENGTH, 5),
                HttpData.of(StandardCharsets.UTF_8, "alice"));
    }

    @Test
    public void toHttpResponseWithoutContent() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(HttpStatus.OK, PLAIN_TEXT_UTF_8,
                                                                    HttpData.EMPTY_DATA);
        final HttpResponse res = aRes.toHttpResponse();
        final List<HttpObject> unaggregated = unaggregate(res);

        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(HttpStatus.OK)
                           .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                           .setInt(CONTENT_LENGTH, 0));
    }

    @Test
    public void toHttpResponseWithTrailingHeaders() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(
                HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bob"),
                HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
        final HttpResponse res = aRes.toHttpResponse();
        final List<HttpObject> unaggregated = unaggregate(res);

        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(HttpStatus.OK)
                           .setObject(CONTENT_TYPE, PLAIN_TEXT_UTF_8)
                           .setInt(CONTENT_LENGTH, 3),
                HttpData.of(StandardCharsets.UTF_8, "bob"),
                HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
    }

    @Test
    public void toHttpResponseWithInformationals() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(
                ImmutableList.of(HttpHeaders.of(HttpStatus.CONTINUE)),
                HttpHeaders.of(HttpStatus.OK), HttpData.EMPTY_DATA, HttpHeaders.EMPTY_HEADERS);

        final HttpResponse res = aRes.toHttpResponse();
        final List<HttpObject> unaggregated = unaggregate(res);

        assertThat(unaggregated).containsExactly(
                HttpHeaders.of(HttpStatus.CONTINUE),
                HttpHeaders.of(HttpStatus.OK)
                           .setInt(CONTENT_LENGTH, 0));
    }

    @Test
    public void toHttpResponseAgainstRequest() {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(HttpMethod.GET, "/qux");
        assertThatThrownBy(aReq::toHttpResponse).isInstanceOf(IllegalStateException.class);
    }

    private static List<HttpObject> unaggregate(StreamMessage<HttpObject> req) throws Exception {
        final List<HttpObject> unaggregated = new ArrayList<>();
        req.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                unaggregated.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        req.completionFuture().get(10, TimeUnit.SECONDS);
        return unaggregated;
    }
}
