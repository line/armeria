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
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.decodePath;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.parseDirectives;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toArmeria;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp1ClientHeader;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp1ServerHeader;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toNettyHttp2ClientHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;

class ArmeriaHttpUtilTest {

    @Test
    void testConcatPaths() throws Exception {
        assertThat(concatPaths(null, "a")).isEqualTo("/a");
        assertThat(concatPaths(null, "/a")).isEqualTo("/a");

        assertThat(concatPaths("", "a")).isEqualTo("/a");
        assertThat(concatPaths("", "/a")).isEqualTo("/a");

        assertThat(concatPaths("/", "a")).isEqualTo("/a");
        assertThat(concatPaths("/", "/a")).isEqualTo("/a");
        assertThat(concatPaths("/", "/")).isEqualTo("/");

        assertThat(concatPaths("/a", "b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a", "/b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a/", "/b")).isEqualTo("/a/b");
    }

    @Test
    void testDecodePath() throws Exception {
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

        toNettyHttp1ClientHeader(in, out, AsciiString::toString);
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

        final Http2Headers out = toNettyHttp2ClientHeader(in);
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
        final Http2Headers in = new DefaultHttp2Headers();
        in.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        final HttpHeaders out = toArmeria(in, true, true);
        assertThat(out.isEndOfStream()).isTrue();

        final HttpHeaders out2 = toArmeria(in, true, false);
        assertThat(out2.isEndOfStream()).isFalse();
    }

    @Test
    void endOfStreamSetEmpty() {
        final Http2Headers in = new DefaultHttp2Headers();
        final HttpHeaders out = toArmeria(in, true, true);
        assertThat(out.isEndOfStream()).isTrue();

        final HttpHeaders out2 = toArmeria(in, true, false);
        assertThat(out2.isEndOfStream()).isFalse();
    }

    @Test
    void inboundCookiesMustBeMergedForHttp2() {
        final Http2Headers in = new DefaultHttp2Headers();

        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.addObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        final HttpHeaders out = toArmeria(in, true, false);

        assertThat(out.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    void stripUserInfo() {
        assertThat(ArmeriaHttpUtil.stripUserInfo("foo")).isEqualTo("foo");
        assertThat(ArmeriaHttpUtil.stripUserInfo("info@foo")).isEqualTo("foo");
        assertThat(ArmeriaHttpUtil.stripUserInfo("@foo.bar")).isEqualTo("foo.bar");
        assertThatThrownBy(() -> ArmeriaHttpUtil.stripUserInfo("info@"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addHostHeaderIfMissing() throws URISyntaxException {
        final io.netty.handler.codec.http.HttpHeaders headers = new DefaultHttpHeaders();
        headers.add(HttpHeaderNames.HOST, "bar");

        final HttpRequest originReq =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello", headers);

        final InetSocketAddress socketAddress = new InetSocketAddress(36462);
        final Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(socketAddress);

        final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);

        RequestHeaders armeriaHeaders = toArmeria(ctx, originReq, serverConfig(), "http");
        assertThat(armeriaHeaders.get(HttpHeaderNames.HOST)).isEqualTo("bar");
        assertThat(armeriaHeaders.authority()).isEqualTo("bar");
        assertThat(armeriaHeaders.scheme()).isEqualTo("http");
        assertThat(armeriaHeaders.path()).isEqualTo("/hello");

        // Remove Host header.
        headers.remove(HttpHeaderNames.HOST);
        armeriaHeaders = toArmeria(ctx, originReq, serverConfig(), "https");
        assertThat(armeriaHeaders.get(HttpHeaderNames.HOST)).isEqualTo("foo:36462"); // The default hostname.
        assertThat(armeriaHeaders.authority()).isEqualTo("foo:36462");
        assertThat(armeriaHeaders.scheme()).isEqualTo("https");
        assertThat(armeriaHeaders.path()).isEqualTo("/hello");
    }

    @Test
    void pathValidation() throws Exception {
        final InetSocketAddress socketAddress = new InetSocketAddress(36462);
        final Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(socketAddress);

        final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);

        // Should not be overly strict, e.g. allow `"` in the path.
        final HttpRequest doubleQuoteReq =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/\"?\"",
                                       new DefaultHttpHeaders());
        RequestHeaders armeriaHeaders = toArmeria(ctx, doubleQuoteReq, serverConfig(), "http");
        assertThat(armeriaHeaders.path()).isEqualTo("/\"?\"");

        // Should accept an asterisk request.
        final HttpRequest asteriskReq =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "*", new DefaultHttpHeaders());
        armeriaHeaders = toArmeria(ctx, asteriskReq, serverConfig(), "http");
        assertThat(armeriaHeaders.path()).isEqualTo("*");

        // Should reject an absolute URI.
        final HttpRequest absoluteUriReq =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                                       "http://example.com/hello", new DefaultHttpHeaders());
        assertThatThrownBy(() -> toArmeria(ctx, absoluteUriReq, serverConfig(), "http"))
                .isInstanceOf(URISyntaxException.class)
                .hasMessageContaining("neither origin form nor asterisk form");

        // Should not accept a path that starts with an asterisk.
        final HttpRequest badAsteriskReq =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "*/", new DefaultHttpHeaders());
        assertThatThrownBy(() -> toArmeria(ctx, badAsteriskReq, serverConfig(), "http"))
                .isInstanceOf(URISyntaxException.class)
                .hasMessageContaining("neither origin form nor asterisk form");
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
        final io.netty.handler.codec.http.HttpHeaders in = new DefaultHttpHeaders();
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
        final HttpHeaders in = HttpHeaders.builder()
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

        toNettyHttp1ServerHeader(in, out);
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
    }

    @Test
    void traditionalHeaderNaming() {
        final HttpHeaders in = HttpHeaders.builder()
                                          .add(HttpHeaderNames.of("foo"), "bar")
                                          .add(HttpHeaderNames.AUTHORIZATION, "dummy")
                                          .add(HttpHeaderNames.CONTENT_LENGTH, "dummy")
                                          .add(HttpHeaderNames.CACHE_CONTROL, "dummy")
                                          .build();

        final io.netty.handler.codec.http.HttpHeaders out =
                new DefaultHttpHeaders();
        toNettyHttp1ClientHeader(in, out, Http1HeaderNaming.traditional());

        assertThat(out).isEqualTo(new DefaultHttpHeaders()
                                          .add("foo", "bar")
                                          .add("Authorization", "dummy")
                                          .add("Content-Length", "dummy")
                                          .add("Cache-Control", "dummy"));
    }

    @Test
    void convertedHeaderTypes() {
        final Http2Headers in = new DefaultHttp2Headers().set("a", "b");

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
        final Http2Headers in = new DefaultHttp2Headers().set("a", "b");

        final InetSocketAddress socketAddress = new InetSocketAddress(36462);
        final Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(socketAddress);

        final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);

        in.set(HttpHeaderNames.METHOD, "GET")
          .set(HttpHeaderNames.PATH, "/");
        // Request headers without pseudo headers.
        final RequestHeaders headers =
                ArmeriaHttpUtil.toArmeriaRequestHeaders(ctx, in, false, "https", serverConfig());
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

    private static ServerConfig serverConfig() {
        final Server server = Server.builder()
                                    .defaultHostname("foo")
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .build();
        return server.config();
    }
}
