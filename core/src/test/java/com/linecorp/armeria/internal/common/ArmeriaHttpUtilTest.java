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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.parseDirectives;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toArmeria;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp1ClientHeaders;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp1ServerHeaders;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp2ClientHeaders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

class ArmeriaHttpUtilTest {

    @Test
    void testConcatPaths() throws Exception {
        assertThat(concatPaths("/", "a")).isEqualTo("/a");
        assertThat(concatPaths("/", "/a")).isEqualTo("/a");
        assertThat(concatPaths("/", "/")).isEqualTo("/");

        assertThat(concatPaths("/a", "b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a", "/b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a/", "/b")).isEqualTo("/a/b");

        assertThat(concatPaths("/a", "")).isEqualTo("/a");
        assertThat(concatPaths("/a/", "")).isEqualTo("/a/");
        assertThat(concatPaths("/a", "?foo=bar")).isEqualTo("/a?foo=bar");
        assertThat(concatPaths("/a/", "?foo=bar")).isEqualTo("/a/?foo=bar");

        // Bad prefixes
        assertThatThrownBy(() -> concatPaths(null, "a")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> concatPaths("", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> concatPaths("relative", "c")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void testDecodePath(boolean isPathParam) throws Exception {
        final Function<String, String> decodeFunc;
        if (isPathParam) {
            decodeFunc = ArmeriaHttpUtil::decodePathParam;
        } else {
            decodeFunc = ArmeriaHttpUtil::decodePath;
        }

        // Fast path
        final String pathThatDoesNotNeedDecode = "/foo_bar_baz";
        assertThat(decodeFunc.apply(pathThatDoesNotNeedDecode)).isSameAs(pathThatDoesNotNeedDecode);

        // Slow path
        assertThat(decodeFunc.apply("/foo%20bar\u007fbaz")).isEqualTo("/foo bar\u007fbaz");
        assertThat(decodeFunc.apply("/%C2%A2")).isEqualTo("/¢"); // Valid UTF-8 sequence
        assertThat(decodeFunc.apply("/%20\u0080")).isEqualTo("/ �"); // Unallowed character
        assertThat(decodeFunc.apply("/%")).isEqualTo("/�"); // No digit
        assertThat(decodeFunc.apply("/%1")).isEqualTo("/�"); // Only a single digit
        assertThat(decodeFunc.apply("/%G0")).isEqualTo("/�"); // First digit is not hex.
        assertThat(decodeFunc.apply("/%0G")).isEqualTo("/�"); // Second digit is not hex.
        assertThat(decodeFunc.apply("/%C3%28")).isEqualTo("/�("); // Invalid UTF-8 sequence

        // %2F (/) must be decoded only for path parameters.
        if (isPathParam) {
            assertThat(decodeFunc.apply("/%2F")).isEqualTo("//");
        } else {
            assertThat(decodeFunc.apply("/%2F")).isEqualTo("/%2F");
        }
    }

    @Test
    void testParseDirectives() {
        final Map<String, String> values = new LinkedHashMap<>();
        final BiConsumer<String, String> cb = (name, value) -> assertThat(values.put(name, value)).isNull();

        // Make sure an effectively empty string does not invoke a callback.
        parseDirectives("", cb);
        assertThat(values).isEmpty();
        parseDirectives(" \t ", cb);
        assertThat(values).isEmpty();
        parseDirectives(" ,,=, =,= ,", cb);
        assertThat(values).isEmpty();

        // Name only.
        parseDirectives("no-cache", cb);
        assertThat(values).hasSize(1).containsEntry("no-cache", null);
        values.clear();
        parseDirectives(" no-cache ", cb);
        assertThat(values).hasSize(1).containsEntry("no-cache", null);
        values.clear();
        parseDirectives("no-cache ,", cb);
        assertThat(values).hasSize(1).containsEntry("no-cache", null);
        values.clear();

        // Name and value.
        parseDirectives("max-age=86400", cb);
        assertThat(values).hasSize(1).containsEntry("max-age", "86400");
        values.clear();
        parseDirectives(" max-age = 86400 ", cb);
        assertThat(values).hasSize(1).containsEntry("max-age", "86400");
        values.clear();
        parseDirectives(" max-age = 86400 ,", cb);
        assertThat(values).hasSize(1).containsEntry("max-age", "86400");
        values.clear();
        parseDirectives("max-age=\"86400\"", cb);
        assertThat(values).hasSize(1).containsEntry("max-age", "86400");
        values.clear();
        parseDirectives(" max-age = \"86400\" ", cb);
        assertThat(values).hasSize(1).containsEntry("max-age", "86400");
        values.clear();
        parseDirectives(" max-age = \"86400\" ,", cb);
        assertThat(values).hasSize(1).containsEntry("max-age", "86400");
        values.clear();

        // Multiple names and values.
        parseDirectives("a,b=c,d,e=\"f\",g", cb);
        assertThat(values).hasSize(5)
                          .containsEntry("a", null)
                          .containsEntry("b", "c")
                          .containsEntry("d", null)
                          .containsEntry("e", "f")
                          .containsEntry("g", null);
    }

    @Test
    void outboundCookiesMustBeMergedForHttp1() {
        final HttpHeaders in = HttpHeaders.builder()
                                          .add(HttpHeaderNames.COOKIE, "a=b; c=d")
                                          .add(HttpHeaderNames.COOKIE, "e=f;g=h")
                                          .addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8)
                                          .add(HttpHeaderNames.COOKIE, "i=j")
                                          .add(HttpHeaderNames.COOKIE, "k=l;")
                                          .build();

        final io.netty.handler.codec.http.HttpHeaders out =
                new DefaultHttpHeaders();

        toNettyHttp1ClientHeaders(in, out, Http1HeaderNaming.ofDefault());
        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    void outboundCookiesMustBeSplitForHttp2() {
        final HttpHeaders in = HttpHeaders.builder()
                                          .add(HttpHeaderNames.COOKIE, "a=b; c=d")
                                          .add(HttpHeaderNames.COOKIE, "e=f;g=h")
                                          .addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8)
                                          .add(HttpHeaderNames.COOKIE, "i=j")
                                          .add(HttpHeaderNames.COOKIE, "k=l;")
                                          .build();

        final Http2Headers out = toNettyHttp2ClientHeaders(in);
        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b", "c=d", "e=f", "g=h", "i=j", "k=l");
    }

    @Test
    void inboundCookiesMustBeMergedForHttp1() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.add(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);

        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    void endOfStreamSet() {
        final Http2Headers in = new ArmeriaHttp2Headers();
        in.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        final HttpHeaders out = toArmeria(in, true, true);
        assertThat(out.isEndOfStream()).isTrue();

        final HttpHeaders out2 = toArmeria(in, true, false);
        assertThat(out2.isEndOfStream()).isFalse();
    }

    @Test
    void endOfStreamSetEmpty() {
        final Http2Headers in = new ArmeriaHttp2Headers();
        final HttpHeaders out = toArmeria(in, true, true);
        assertThat(out.isEndOfStream()).isTrue();

        final HttpHeaders out2 = toArmeria(in, true, false);
        assertThat(out2.isEndOfStream()).isFalse();
    }

    @Test
    void inboundCookiesMustBeMergedForHttp2() {
        final Http2Headers in = new ArmeriaHttp2Headers();

        in.add(HttpHeaderNames.METHOD, "GET");
        in.add(HttpHeaderNames.SCHEME, "http");
        in.add(HttpHeaderNames.AUTHORITY, "foo.com");
        in.add(HttpHeaderNames.PATH, "/");
        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final RequestTarget reqTarget = RequestTarget.forServer(in.path().toString());
        final RequestHeaders out = ArmeriaHttpUtil.toArmeriaRequestHeaders(
                null, in, false, "http", null, reqTarget);

        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f;g=h; i=j; k=l;");
    }

    @Test
    void stripTEHeaders() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out).isEmpty();
    }

    @Test
    void stripTEHeadersExcludingTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);
        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS);
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripTEHeadersCsvSeparatedExcludingTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS);
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripTEHeadersCsvSeparatedAccountsForValueSimilarToTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS + "foo");
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out.contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    void stripTEHeadersAccountsForValueSimilarToTrailers() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS + "foo");
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out.contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    void stripTEHeadersAccountsForOWS() {
        // Disable headers validation to allow optional whitespace.
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders(false);
        in.add(HttpHeaderNames.TE, " " + HttpHeaderValues.TRAILERS + ' ');
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripConnectionHeadersAndNominees() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.CONNECTION, "foo");
        in.add("foo", "bar");
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out).isEmpty();
    }

    @Test
    void stripConnectionNomineesWithCsv() {
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
        in.add(HttpHeaderNames.CONNECTION, "foo,  bar");
        in.add("foo", "baz");
        in.add("bar", "qux");
        in.add("hello", "world");
        final HttpHeadersBuilder out = HttpHeaders.builder();
        toArmeria(in, out);
        assertThat(out).hasSize(1);
        assertThat(out.get(HttpHeaderNames.of("hello"))).isEqualTo("world");
    }

    @Test
    void excludeDisallowedHeadersWhileHttp2ToHttp1() {
        final ResponseHeaders in = ResponseHeaders.builder()
                                                  .add(HttpHeaderNames.TRAILER, "foo")
                                                  .add(HttpHeaderNames.HOST, "bar")
                                                  .add(HttpHeaderNames.PATH, "dummy")
                                                  .add(HttpHeaderNames.METHOD, "dummy")
                                                  .add(HttpHeaderNames.SCHEME, "dummy")
                                                  .add(HttpHeaderNames.STATUS, "dummy")
                                                  .add(HttpHeaderNames.TRANSFER_ENCODING, "dummy")
                                                  .add(ExtensionHeaderNames.STREAM_ID.text(), "dummy")
                                                  .add(ExtensionHeaderNames.SCHEME.text(), "dummy")
                                                  .add(ExtensionHeaderNames.PATH.text(), "dummy")
                                                  .build();

        final io.netty.handler.codec.http.HttpHeaders out =
                new DefaultHttpHeaders();

        toNettyHttp1ServerHeaders(in, out, Http1HeaderNaming.ofDefault(), true);
        assertThat(out).isEqualTo(new DefaultHttpHeaders()
                                          .add(io.netty.handler.codec.http.HttpHeaderNames.TRAILER, "foo")
                                          .add(io.netty.handler.codec.http.HttpHeaderNames.HOST, "bar"));
    }

    @Test
    void excludeDisallowedInTrailers() {
        final HttpHeaders in = HttpHeaders.builder()
                                          .add(HttpHeaderNames.of("foo"), "bar")
                                          .add(HttpHeaderNames.TRANSFER_ENCODING, "dummy")
                                          .add(HttpHeaderNames.CONTENT_LENGTH, "dummy")
                                          .add(HttpHeaderNames.CACHE_CONTROL, "dummy")
                                          .add(HttpHeaderNames.EXPECT, "dummy")
                                          .add(HttpHeaderNames.HOST, "dummy")
                                          .add(HttpHeaderNames.MAX_FORWARDS, "dummy")
                                          .add(HttpHeaderNames.PRAGMA, "dummy")
                                          .add(HttpHeaderNames.RANGE, "dummy")
                                          .add(HttpHeaderNames.TE, "dummy")
                                          .add(HttpHeaderNames.WWW_AUTHENTICATE, "dummy")
                                          .add(HttpHeaderNames.AUTHORIZATION, "dummy")
                                          .add(HttpHeaderNames.PROXY_AUTHENTICATE, "dummy")
                                          .add(HttpHeaderNames.PROXY_AUTHORIZATION, "dummy")
                                          .add(HttpHeaderNames.DATE, "dummy")
                                          .add(HttpHeaderNames.LOCATION, "dummy")
                                          .add(HttpHeaderNames.RETRY_AFTER, "dummy")
                                          .add(HttpHeaderNames.VARY, "dummy")
                                          .add(HttpHeaderNames.WARNING, "dummy")
                                          .add(HttpHeaderNames.CONTENT_ENCODING, "dummy")
                                          .add(HttpHeaderNames.CONTENT_TYPE, "dummy")
                                          .add(HttpHeaderNames.CONTENT_RANGE, "dummy")
                                          .add(HttpHeaderNames.TRAILER, "dummy")
                                          .build();
        final Http2Headers nettyHeaders = ArmeriaHttpUtil.toNettyHttp2ServerTrailers(in);
        assertThat(nettyHeaders.size()).isOne();
        assertThat(nettyHeaders.get("foo")).isEqualTo("bar");
    }

    @Test
    void excludeDisallowedInResponseHeaders() {
        final ResponseHeadersBuilder in = ResponseHeaders.builder()
                                                         .add(HttpHeaderNames.STATUS, "200")
                                                         .add(HttpHeaderNames.AUTHORITY, "dummy")
                                                         .add(HttpHeaderNames.METHOD, "dummy")
                                                         .add(HttpHeaderNames.PATH, "dummy")
                                                         .add(HttpHeaderNames.SCHEME, "dummy");
        final Http2Headers nettyHeaders = ArmeriaHttpUtil.toNettyHttp2ServerHeaders(in);
        assertThat(nettyHeaders.size()).isOne();
        assertThat(nettyHeaders.get(HttpHeaderNames.STATUS)).isEqualTo("200");
    }

    @Test
    void traditionalHeaderNaming() {
        final HttpHeaders in = HttpHeaders.builder()
                                          .add(HttpHeaderNames.of("foo"), "bar")
                                          .add(HttpHeaderNames.AUTHORIZATION, "dummy")
                                          .add(HttpHeaderNames.CONTENT_LENGTH, "dummy")
                                          .add(HttpHeaderNames.CACHE_CONTROL, "dummy")
                                          .build();

        final io.netty.handler.codec.http.HttpHeaders clientOutHeaders =
                new DefaultHttpHeaders();
        toNettyHttp1ClientHeaders(in, clientOutHeaders, Http1HeaderNaming.traditional());
        assertThat(clientOutHeaders).isEqualTo(new DefaultHttpHeaders()
                                                       .add("foo", "bar")
                                                       .add("Authorization", "dummy")
                                                       .add("Content-Length", "dummy")
                                                       .add("Cache-Control", "dummy"));

        final ResponseHeaders responseHeaders = ResponseHeaders.builder(200).add(in).build();
        final io.netty.handler.codec.http.HttpHeaders serverOutHeaders =
                new DefaultHttpHeaders();
        toNettyHttp1ServerHeaders(responseHeaders, serverOutHeaders, Http1HeaderNaming.traditional(), true);
        // 200 status is included in the status-line.
        assertThat(serverOutHeaders).isEqualTo(new DefaultHttpHeaders()
                                                       .add("foo", "bar")
                                                       .add("Authorization", "dummy")
                                                       .add("Content-Length", "dummy")
                                                       .add("Cache-Control", "dummy"));
    }

    @Test
    void convertedHeaderTypes() {
        final Http2Headers in = new ArmeriaHttp2Headers().set("a", "b");

        // Request headers without pseudo headers.
        assertThat(toArmeria(in, true, false)).isInstanceOf(HttpHeaders.class)
                                              .isNotInstanceOf(RequestHeaders.class)
                                              .isNotInstanceOf(ResponseHeaders.class);

        // Response headers without pseudo headers.
        assertThat(toArmeria(in, false, false)).isInstanceOf(HttpHeaders.class)
                                               .isNotInstanceOf(RequestHeaders.class)
                                               .isNotInstanceOf(ResponseHeaders.class);

        // Request headers with pseudo headers.
        in.clear()
          .set(HttpHeaderNames.METHOD, "GET")
          .set(HttpHeaderNames.PATH, "/");
        assertThat(toArmeria(in, true, false)).isInstanceOf(RequestHeaders.class)
                                              .isNotInstanceOf(ResponseHeaders.class);

        // Response headers with pseudo headers.
        in.clear()
          .set(HttpHeaderNames.STATUS, "200");
        assertThat(toArmeria(in, false, false)).isInstanceOf(ResponseHeaders.class)
                                               .isNotInstanceOf(RequestHeaders.class);

        // Request headers with mixed pseudo headers.
        in.clear()
          .set(HttpHeaderNames.METHOD, "GET")
          .set(HttpHeaderNames.PATH, "/")
          .set(HttpHeaderNames.STATUS, "200");
        assertThat(toArmeria(in, true, false)).isInstanceOf(RequestHeaders.class)
                                              .isNotInstanceOf(ResponseHeaders.class);

        // Response headers with mixed pseudo headers.
        in.clear()
          .set(HttpHeaderNames.STATUS, "200")
          .set(HttpHeaderNames.METHOD, "GET");
        assertThat(toArmeria(in, false, false)).isInstanceOf(ResponseHeaders.class)
                                               .isNotInstanceOf(RequestHeaders.class);
    }

    @Test
    void toArmeriaRequestHeaders() {
        final Http2Headers in = new ArmeriaHttp2Headers().set("a", "b");

        final ChannelHandlerContext ctx = mockChannelHandlerContext();

        in.set(HttpHeaderNames.METHOD, "GET")
          .set(HttpHeaderNames.PATH, "/");
        // Request headers without pseudo headers.
        final RequestTarget reqTarget = RequestTarget.forServer(in.path().toString());
        final RequestHeaders headers =
                ArmeriaHttpUtil.toArmeriaRequestHeaders(ctx, in, false, "https",
                                                        serverConfig(), reqTarget);
        assertThat(headers.scheme()).isEqualTo("https");
        assertThat(headers.authority()).isEqualTo("foo:36462");
    }

    @Test
    void isAbsoluteUri() {
        final String good = "none+http://a.com";
        assertThat(ArmeriaHttpUtil.isAbsoluteUri(good)).isTrue();
        final List<String> bad = Arrays.asList(
                "none+http:/a",
                "//a",
                "://a",
                "a/b://c",
                "http://",
                "://",
                "",
                null);
        bad.forEach(path -> assertThat(ArmeriaHttpUtil.isAbsoluteUri(path)).isFalse());
    }

    @Test
    void serverHeader() {
        final String pattern = "Armeria/(\\d+).(\\d+).(\\d+)(-SNAPSHOT)?";
        assertThat("Armeria/1.0.0").containsPattern(pattern);
        assertThat("Armeria/1.0.0-SNAPSHOT").containsPattern(pattern);
        assertThat(ArmeriaHttpUtil.SERVER_HEADER).containsPattern(pattern);
    }

    @Test
    void disallowedResponseHeaderNames() {
        for (AsciiString headerName : ImmutableList.of(HttpHeaderNames.METHOD,
                                                       HttpHeaderNames.AUTHORITY,
                                                       HttpHeaderNames.SCHEME,
                                                       HttpHeaderNames.PATH,
                                                       HttpHeaderNames.PROTOCOL)) {
            assertThat(ArmeriaHttpUtil.disallowedResponseHeaderNames().contains(headerName)).isTrue();
        }
        assertThat(ArmeriaHttpUtil.disallowedResponseHeaderNames()).doesNotContain(HttpHeaderNames.STATUS);
        assertThat(ArmeriaHttpUtil.disallowedResponseHeaderNames()).doesNotContain(HttpHeaderNames.LOCATION);
    }

    @Test
    void shouldReturnConnectionCloseWithNoKeepAlive() {
        final ResponseHeaders in = ResponseHeaders.builder(HttpStatus.OK)
                                                  .contentType(MediaType.JSON)
                                                  .build();
        final io.netty.handler.codec.http.HttpHeaders out =
                new DefaultHttpHeaders();

        toNettyHttp1ServerHeaders(in, out, Http1HeaderNaming.ofDefault(), false);
        assertThat(out).isEqualTo(new DefaultHttpHeaders()
                                          .add(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON.toString())
                                          .add(HttpHeaderNames.CONNECTION, "close"));
    }

    private static ServerConfig serverConfig() {
        final Server server = Server.builder()
                                    .defaultHostname("foo")
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .build();
        return server.config();
    }

    private static ChannelHandlerContext mockChannelHandlerContext() {
        final InetSocketAddress socketAddress = new InetSocketAddress(36462);
        final Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(socketAddress);

        final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        return ctx;
    }
}
