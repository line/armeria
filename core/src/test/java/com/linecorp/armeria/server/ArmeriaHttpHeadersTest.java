package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;

class ArmeriaHttpHeadersTest {
    @Test
    void inboundCookiesMustBeMergedForHttp1() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());
        in.add(HttpHeaderNames.COOKIE, "a=b; c=d");
        in.add(HttpHeaderNames.COOKIE, "e=f;g=h");
        in.add(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.toString());
        in.add(HttpHeaderNames.COOKIE, "i=j");
        in.add(HttpHeaderNames.COOKIE, "k=l;");

        assertThat(in.getAll(HttpHeaderNames.COOKIE))
                .containsExactly("a=b; c=d; e=f; g=h; i=j; k=l");
    }

    @Test
    void stripTEHeaders() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);

        assertThat(in).isEmpty();
    }

    @Test
    void stripTEHeadersExcludingTrailers() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP);
        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS);

        assertThat(in.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripTEHeadersCsvSeparatedExcludingTrailers() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS);

        assertThat(in.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripTEHeadersCsvSeparatedAccountsForValueSimilarToTrailers() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.TE, HttpHeaderValues.GZIP + "," + HttpHeaderValues.TRAILERS + "foo");

        assertThat(in.contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    void stripTEHeadersAccountsForValueSimilarToTrailers() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS + "foo");

        assertThat(in.contains(HttpHeaderNames.TE)).isFalse();
    }

    @Test
    void stripTEHeadersAccountsForOWS() {
        // Disable headers validation to allow optional whitespace.
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.TE, " " + HttpHeaderValues.TRAILERS + ' ');

        assertThat(in.get(HttpHeaderNames.TE)).isEqualTo(HttpHeaderValues.TRAILERS.toString());
    }

    @Test
    void stripConnectionHeadersAndNominees() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.CONNECTION, "foo");
        in.add("foo", "bar");

        assertThat(in).isEmpty();
    }

    @Test
    void stripConnectionNomineesWithCsv() {
        final HttpHeaders in = new ArmeriaHttpHeaders(RequestHeaders.builder());

        in.add(HttpHeaderNames.CONNECTION, "foo,  bar");
        in.add("foo", "baz");
        in.add("bar", "qux");
        in.add("hello", "world");

        assertThat(in).hasSize(1);
        assertThat(in.get(HttpHeaderNames.of("hello"))).isEqualTo("world");
    }
}