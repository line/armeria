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
import java.util.List;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class DefaultAggregatedHttpMessageTest {

    @Test
    public void toHttpRequest() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.POST, "/foo", PLAIN_TEXT_UTF_8, "bar");
        final HttpRequest req = HttpRequest.of(aReq);
        final List<HttpObject> drained = req.drainAll().join();

        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.POST, "/foo",
                                                              CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                              CONTENT_LENGTH, 3));
        assertThat(drained).containsExactly(HttpData.of(StandardCharsets.UTF_8, "bar"));
    }

    @Test
    public void toHttpRequestWithoutContent() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(HttpMethod.GET, "/bar");
        final HttpRequest req = HttpRequest.of(aReq);
        final List<HttpObject> drained = req.drainAll().join();

        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.GET, "/bar"));
        assertThat(drained).isEmpty();
    }

    @Test
    public void toHttpRequestWithTrailingHeaders() throws Exception {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(
                HttpMethod.PUT, "/baz", PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
        final HttpRequest req = HttpRequest.of(aReq);
        final List<HttpObject> drained = req.drainAll().join();

        assertThat(req.headers()).isEqualTo(RequestHeaders.of(HttpMethod.PUT, "/baz",
                                                              CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                                              CONTENT_LENGTH, 3));
        assertThat(drained).containsExactly(
                HttpData.of(StandardCharsets.UTF_8, "bar"),
                HttpHeaders.of(CONTENT_MD5, "37b51d194a7513e45b56f6524f2d51f2"));
    }

    @Test
    public void toHttpRequestAgainstResponse() {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(200);
        assertThatThrownBy(() -> HttpRequest.of(aRes)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void toHttpRequestWithoutPath() {
        // Method only
        assertThatThrownBy(() -> HttpRequest.of(
                AggregatedHttpMessage.of(HttpHeaders.of(HttpHeaderNames.METHOD, "GET"))))
                .isInstanceOf(IllegalArgumentException.class);

        // Path only
        assertThatThrownBy(() -> HttpRequest.of(
                AggregatedHttpMessage.of(HttpHeaders.of(HttpHeaderNames.PATH, "/charlie"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void toHttpResponse() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(
                HttpStatus.OK, PLAIN_TEXT_UTF_8, "alice");
        final HttpResponse res = HttpResponse.of(aRes);
        final List<HttpObject> drained = res.drainAll().join();

        assertThat(drained).containsExactly(
                ResponseHeaders.of(HttpStatus.OK,
                                   CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                   CONTENT_LENGTH, 5),
                HttpData.of(StandardCharsets.UTF_8, "alice"));
    }

    @Test
    public void toHttpResponseWithoutContent() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(HttpStatus.OK, PLAIN_TEXT_UTF_8,
                                                                    HttpData.EMPTY_DATA);
        final HttpResponse res = HttpResponse.of(aRes);
        final List<HttpObject> drained = res.drainAll().join();

        assertThat(drained).containsExactly(
                ResponseHeaders.of(HttpStatus.OK,
                                   CONTENT_TYPE, PLAIN_TEXT_UTF_8,
                                   CONTENT_LENGTH, 0));
    }

    @Test
    public void toHttpResponseWithTrailingHeaders() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(
                HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8("bob"),
                HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
        final HttpResponse res = HttpResponse.of(aRes);
        final List<HttpObject> drained = res.drainAll().join();

        assertThat(drained).containsExactly(
                ResponseHeaders.of(HttpStatus.OK,
                                   CONTENT_TYPE, PLAIN_TEXT_UTF_8),
                HttpData.of(StandardCharsets.UTF_8, "bob"),
                HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
    }

    @Test
    public void toHttpResponseWithInformationals() throws Exception {
        final AggregatedHttpMessage aRes = AggregatedHttpMessage.of(
                ImmutableList.of(ResponseHeaders.of(HttpStatus.CONTINUE)),
                ResponseHeaders.of(HttpStatus.OK), HttpData.EMPTY_DATA, HttpHeaders.of());

        final HttpResponse res = HttpResponse.of(aRes);
        final List<HttpObject> drained = res.drainAll().join();

        assertThat(drained).containsExactly(
                ResponseHeaders.of(HttpStatus.CONTINUE),
                ResponseHeaders.of(HttpStatus.OK, CONTENT_LENGTH, 0));
    }

    @Test
    public void errorWhenContentOrTrailingHeadersShouldBeEmpty() throws Exception {
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.CONTINUE, HttpData.ofUtf8("bob"),
                                               HttpHeaders.of());
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.NO_CONTENT, HttpData.ofUtf8("bob"),
                                               HttpHeaders.of());
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.RESET_CONTENT, HttpData.ofUtf8("bob"),
                                               HttpHeaders.of());
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.NOT_MODIFIED, HttpData.ofUtf8("bob"),
                                               HttpHeaders.of());

        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.CONTINUE, HttpData.EMPTY_DATA,
                                               HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.NO_CONTENT, HttpData.EMPTY_DATA,
                                               HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.RESET_CONTENT, HttpData.EMPTY_DATA,
                                               HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
        contentAndTrailingHeadersShouldBeEmpty(HttpStatus.NOT_MODIFIED, HttpData.EMPTY_DATA,
                                               HttpHeaders.of(CONTENT_MD5, "9f9d51bc70ef21ca5c14f307980a29d8"));
    }

    private static void contentAndTrailingHeadersShouldBeEmpty(HttpStatus status, HttpData content,
                                                               HttpHeaders trailingHeaders) {
        assertThatThrownBy(() -> AggregatedHttpMessage.of(status, PLAIN_TEXT_UTF_8, content, trailingHeaders))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void contentLengthIsNotSetWhen1xxOr204Or205() {
        ResponseHeaders headers = ResponseHeaders.of(HttpStatus.CONTINUE, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpMessage.of(headers).headers().get(CONTENT_LENGTH)).isNull();

        headers = ResponseHeaders.of(HttpStatus.NO_CONTENT, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpMessage.of(headers).headers().get(CONTENT_LENGTH)).isNull();

        headers = ResponseHeaders.of(HttpStatus.RESET_CONTENT, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpMessage.of(headers).headers().get(CONTENT_LENGTH)).isNull();

        // 304 response can have the 'Content-length' header when it is a response to a conditional
        // GET request. See https://tools.ietf.org/html/rfc7230#section-3.3.2
        headers = ResponseHeaders.of(HttpStatus.NOT_MODIFIED, CONTENT_LENGTH, 100);
        assertThat(AggregatedHttpMessage.of(headers).headers().getInt(CONTENT_LENGTH)).isEqualTo(100);
    }

    @Test
    public void contentLengthIsSet() {
        AggregatedHttpMessage msg = AggregatedHttpMessage.of(HttpStatus.OK);
        assertThat(msg.headers().getInt(CONTENT_LENGTH)).isEqualTo(6); // the length of status.toHttpData()

        msg = AggregatedHttpMessage.of(HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8("foo"));
        assertThat(msg.headers().getInt(CONTENT_LENGTH)).isEqualTo(3);

        msg = AggregatedHttpMessage.of(HttpStatus.OK, PLAIN_TEXT_UTF_8, HttpData.ofUtf8(""));
        assertThat(msg.headers().getInt(CONTENT_LENGTH)).isEqualTo(0);

        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK, CONTENT_LENGTH, 1000000);
        // It can have 'Content-length' even though it does not have content, because it can be a response
        // to a HEAD request.
        assertThat(AggregatedHttpMessage.of(headers).headers().getInt(CONTENT_LENGTH)).isEqualTo(1000000);

        msg = AggregatedHttpMessage.of(headers, HttpData.ofUtf8("foo"));
        assertThat(msg.headers().getInt(CONTENT_LENGTH)).isEqualTo(3); // The length is reset to 3 from 1000000.
    }

    @Test
    public void toHttpResponseAgainstRequest() {
        final AggregatedHttpMessage aReq = AggregatedHttpMessage.of(HttpMethod.GET, "/qux");
        assertThatThrownBy(() -> HttpResponse.of(aReq)).isInstanceOf(IllegalArgumentException.class);
    }
}
