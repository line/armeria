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

package com.linecorp.armeria.internal;

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.setHttp2Authority;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.toArmeria;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.toNettyHttp1;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.toNettyHttp2;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

public class ArmeriaHttpUtilTest {
    @Test
    public void testConcatPaths() throws Exception {
        assertThat(concatPaths(null, "a")).isEqualTo("/a");
        assertThat(concatPaths(null, "/a")).isEqualTo("/a");

        assertThat(concatPaths("", "a")).isEqualTo("/a");
        assertThat(concatPaths("", "/a")).isEqualTo("/a");

        assertThat(concatPaths("/", "a")).isEqualTo("/a");
        assertThat(concatPaths("/", "/a")).isEqualTo("/a");

        assertThat(concatPaths("/a", "b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a", "/b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a/", "/b")).isEqualTo("/a/b");
    }

    @Test
    public void outboundCookiesMustBeMergedForHttp1() throws Http2Exception {
        final HttpHeaders in = new DefaultHttpHeaders();

        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final io.netty.handler.codec.http.HttpHeaders out =
                new io.netty.handler.codec.http.DefaultHttpHeaders();

        toNettyHttp1(0, in, out, HttpVersion.HTTP_1_1, false, true);
        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    public void outboundCookiesMustBeSplitForHttp2() {
        final HttpHeaders in = new DefaultHttpHeaders();

        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final Http2Headers out = toNettyHttp2(in);
        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b", "c=d", "e=f", "g=h", "i=j", "k=l");
    }

    @Test
    public void inboundCookiesMustBeMergedForHttp1() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        final HttpHeaders out = new DefaultHttpHeaders();

        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.add(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        toArmeria(in, out);

        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    public void inboundCookiesMustBeMergedForHttp2() {
        final Http2Headers in = new DefaultHttp2Headers();

        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final HttpHeaders out = toArmeria(in);

        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    public void setHttp2AuthorityWithoutUserInfo() {
        HttpHeaders headers = new DefaultHttpHeaders();

        setHttp2Authority("foo", headers);
        assertThat(headers.authority()).isEqualTo("foo");
    }

    @Test
    public void setHttp2AuthorityWithUserInfo() {
        HttpHeaders headers = new DefaultHttpHeaders();

        setHttp2Authority("info@foo", headers);
        assertThat(headers.authority()).isEqualTo("foo");

        setHttp2Authority("@foo.bar", headers);
        assertThat(headers.authority()).isEqualTo("foo.bar");
    }

    @Test
    public void setHttp2AuthorityNullOrEmpty() {
        HttpHeaders headers = new DefaultHttpHeaders();

        setHttp2Authority(null, headers);
        assertThat(headers.authority()).isNull();

        setHttp2Authority("", headers);
        assertThat(headers.authority()).isEmpty();
    }

    @Test(expected = IllegalArgumentException.class)
    public void setHttp2AuthorityWithEmptyAuthority() {
        setHttp2Authority("info@", new DefaultHttpHeaders());
    }

    @Test
    public void stripTEHeaders() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out).isEmpty();
    }

    @Test
    public void stripTEHeadersExcludingTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);
        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS);
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    public void stripTEHeadersCsvSeparatedExcludingTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS);
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    public void stripTEHeadersCsvSeparatedAccountsForValueSimilarToTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS + "foo");
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out.contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    public void stripTEHeadersAccountsForValueSimilarToTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS + "foo");
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out.contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    public void stripTEHeadersAccountsForOWS() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, " " + HttpHeaderValues.TRAILERS + ' ');
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    public void stripConnectionHeadersAndNominees() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.CONNECTION, "foo");
        in.add("foo", "bar");
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out).isEmpty();
    }

    @Test
    public void stripConnectionNomineesWithCsv() {
        final io.netty.handler.codec.http.HttpHeaders in = new io.netty.handler.codec.http.DefaultHttpHeaders();
        in.add(HttpHeaderNames.CONNECTION, "foo,  bar");
        in.add("foo", "baz");
        in.add("bar", "qux");
        in.add("hello", "world");
        final HttpHeaders out = new DefaultHttpHeaders();
        toArmeria(in, out);
        assertThat(out).hasSize(1);
        assertThat(out.get(AsciiString.of("hello"))).isEqualTo("world");
    }
}
