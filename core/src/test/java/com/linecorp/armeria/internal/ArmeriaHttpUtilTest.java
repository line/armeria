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
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.decodePath;
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
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;

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
    public void testDecodePath() throws Exception {
        // Fast path
        final String pathThatDoesNotNeedDecode = "/foo_bar_baz";
        assertThat(decodePath(pathThatDoesNotNeedDecode)).isSameAs(pathThatDoesNotNeedDecode);

        // Slow path
        assertThat(decodePath("/foo%20bar\u007fbaz")).isEqualTo("/foo bar\u007fbaz");
        assertThat(decodePath("/%C2%A2")).isEqualTo("/¢"); // Valid UTF-8 sequence
        assertThat(decodePath("/%20\u0080")).isEqualTo("/ �"); // Unallowed character
        assertThat(decodePath("/%")).isEqualTo("/�"); // No digit
        assertThat(decodePath("/%1")).isEqualTo("/�"); // Only a single digit
        assertThat(decodePath("/%G0")).isEqualTo("/�"); // First digit is not hex.
        assertThat(decodePath("/%0G")).isEqualTo("/�"); // Second digit is not hex.
        assertThat(decodePath("/%C3%28")).isEqualTo("/�("); // Invalid UTF-8 sequence
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

        final Http2Headers out = toNettyHttp2(in, true);
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
    public void endOfStreamSet() {
        final Http2Headers in = new DefaultHttp2Headers();
        final HttpHeaders out = toArmeria(in, true);
        assertThat(out.isEndOfStream()).isTrue();

        final HttpHeaders out2 = toArmeria(in, false);
        assertThat(out2.isEndOfStream()).isFalse();
    }

    @Test
    public void inboundCookiesMustBeMergedForHttp2() {
        final Http2Headers in = new DefaultHttp2Headers();

        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final HttpHeaders out = toArmeria(in, false);

        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    public void setHttp2AuthorityWithoutUserInfo() {
        final HttpHeaders headers = new DefaultHttpHeaders();

        setHttp2Authority("foo", headers);
        assertThat(headers.authority()).isEqualTo("foo");
    }

    @Test
    public void setHttp2AuthorityWithUserInfo() {
        final HttpHeaders headers = new DefaultHttpHeaders();

        setHttp2Authority("info@foo", headers);
        assertThat(headers.authority()).isEqualTo("foo");

        setHttp2Authority("@foo.bar", headers);
        assertThat(headers.authority()).isEqualTo("foo.bar");
    }

    @Test
    public void setHttp2AuthorityNullOrEmpty() {
        final HttpHeaders headers = new DefaultHttpHeaders();

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
        assertThat(out.get(HttpHeaderNames.of("hello"))).isEqualTo("world");
    }

    @Test
    public void excludeBlacklistHeadersWhileHttp2ToHttp1() throws Http2Exception {
        final HttpHeaders in = new DefaultHttpHeaders();

        in.add(HttpHeaderNames.TRAILER, "foo");
        in.add(HttpHeaderNames.AUTHORITY, "bar"); // Translated to host
        in.add(HttpHeaderNames.PATH, "dummy");
        in.add(HttpHeaderNames.METHOD, "dummy");
        in.add(HttpHeaderNames.SCHEME, "dummy");
        in.add(HttpHeaderNames.STATUS, "dummy");
        in.add(HttpHeaderNames.TRANSFER_ENCODING, "dummy");
        in.add(ExtensionHeaderNames.STREAM_ID.text(), "dummy");
        in.add(ExtensionHeaderNames.SCHEME.text(), "dummy");
        in.add(ExtensionHeaderNames.PATH.text(), "dummy");

        final io.netty.handler.codec.http.HttpHeaders out =
                new io.netty.handler.codec.http.DefaultHttpHeaders();

        toNettyHttp1(0, in, out, HttpVersion.HTTP_1_1, false, false);
        assertThat(out).isEqualTo(new io.netty.handler.codec.http.DefaultHttpHeaders()
                                          .add(io.netty.handler.codec.http.HttpHeaderNames.TRAILER, "foo")
                                          .add(io.netty.handler.codec.http.HttpHeaderNames.HOST, "bar"));
    }

    @Test
    public void excludeBlacklistInTrailingHeaders() throws Http2Exception {
        final HttpHeaders in = new DefaultHttpHeaders();

        in.add(HttpHeaderNames.of("foo"), "bar");
        in.add(HttpHeaderNames.TRANSFER_ENCODING, "dummy");
        in.add(HttpHeaderNames.CONTENT_LENGTH, "dummy");
        in.add(HttpHeaderNames.CACHE_CONTROL, "dummy");
        in.add(HttpHeaderNames.EXPECT, "dummy");
        in.add(HttpHeaderNames.HOST, "dummy");
        in.add(HttpHeaderNames.MAX_FORWARDS, "dummy");
        in.add(HttpHeaderNames.PRAGMA, "dummy");
        in.add(HttpHeaderNames.RANGE, "dummy");
        in.add(HttpHeaderNames.TE, "dummy");
        in.add(HttpHeaderNames.WWW_AUTHENTICATE, "dummy");
        in.add(HttpHeaderNames.AUTHORIZATION, "dummy");
        in.add(HttpHeaderNames.PROXY_AUTHENTICATE, "dummy");
        in.add(HttpHeaderNames.PROXY_AUTHORIZATION, "dummy");
        in.add(HttpHeaderNames.DATE, "dummy");
        in.add(HttpHeaderNames.LOCATION, "dummy");
        in.add(HttpHeaderNames.RETRY_AFTER, "dummy");
        in.add(HttpHeaderNames.VARY, "dummy");
        in.add(HttpHeaderNames.WARNING, "dummy");
        in.add(HttpHeaderNames.CONTENT_ENCODING, "dummy");
        in.add(HttpHeaderNames.CONTENT_TYPE, "dummy");
        in.add(HttpHeaderNames.CONTENT_RANGE, "dummy");
        in.add(HttpHeaderNames.TRAILER, "dummy");

        final io.netty.handler.codec.http.HttpHeaders outHttp1 =
                new io.netty.handler.codec.http.DefaultHttpHeaders();

        toNettyHttp1(0, in, outHttp1, HttpVersion.HTTP_1_1, true, false);
        assertThat(outHttp1).isEqualTo(new io.netty.handler.codec.http.DefaultHttpHeaders().add("foo", "bar"));

        final Http2Headers outHttp2 = toNettyHttp2(in, true);
        assertThat(outHttp2).isEqualTo(new DefaultHttp2Headers().add("foo", "bar"));
    }
}
