/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.servlet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;

import io.netty.util.AsciiString;

final class DefaultServletHttpResponse implements HttpServletResponse {

    private final DefaultServletContext servletContext;
    private final CompletableFuture<HttpResponse> resFuture;
    private final DefaultServletOutputStream outputStream;
    private final PrintWriter writer;
    private final ResponseHeadersBuilder headersBuilder;

    private final List<String> setCookies = new ArrayList<>();

    private final ByteArrayOutputStream content = new ByteArrayOutputStream();

    private boolean usingOutputStream;

    private boolean usingWriter;

    DefaultServletHttpResponse(DefaultServletContext servletContext,
                               CompletableFuture<HttpResponse> resFuture) {
        this.servletContext = servletContext;
        this.resFuture = resFuture;
        outputStream = new DefaultServletOutputStream(this);
        writer = new PrintWriter(outputStream);
        final MediaType contentType = MediaType.HTML_UTF_8.withCharset(
                Charset.forName(servletContext.getResponseCharacterEncoding()));
        headersBuilder = ResponseHeaders.builder(HttpStatus.OK).contentType(contentType);
    }

    boolean isReady() {
        return !resFuture.isDone();
    }

    void close() {
        if (resFuture.isDone()) {
            return;
        }

        if (!setCookies.isEmpty()) {
            headersBuilder.set(HttpHeaderNames.SET_COOKIE, setCookies);
        }
        if (content.size() == 0) {
            resFuture.complete(HttpResponse.of(
                    headersBuilder.removeAndThen(HttpHeaderNames.CONTENT_TYPE).build()));
        } else {
            resFuture.complete(HttpResponse.of(headersBuilder.build(), HttpData.wrap(content.toByteArray())));
        }
    }

    void write(byte[] data) throws IOException {
        content.write(data);
    }

    @Override
    public void addCookie(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        final String path = cookie.getPath() == null ? "/" : cookie.getPath();
        final CookieBuilder builder =
                com.linecorp.armeria.common.Cookie.builder(cookie.getName(), cookie.getValue())
                                                  .path(path)
                                                  .httpOnly(
                                                          cookie.isHttpOnly())
                                                  .secure(cookie.getSecure());
        if (cookie.getMaxAge() != -1) {
            builder.maxAge(cookie.getMaxAge());
        }
        if (!isNullOrEmpty(cookie.getDomain())) {
            builder.domain(cookie.getDomain());
        }
        setCookies.add(builder.build().toSetCookieHeader());
    }

    @Override
    public boolean containsHeader(String name) {
        requireNonNull(name, "name");
        return headersBuilder.contains(name);
    }

    @Override
    public String encodeURL(String url) {
        requireNonNull(url, "url");
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        requireNonNull(url, "url");
        return encodeURL(url);
    }

    @Override
    @Deprecated
    public String encodeUrl(String url) {
        requireNonNull(url, "url");
        return encodeURL(url);
    }

    @Override
    @Deprecated
    public String encodeRedirectUrl(String url) {
        requireNonNull(url, "url");
        return encodeRedirectURL(url);
    }

    @Override
    public void sendError(int sc, @Nullable String msg) throws IOException {
        if (resFuture.isDone()) {
            throw new IllegalStateException("response already sent");
        }
        final ResponseHeaders headers = ResponseHeaders.builder(sc).contentType(MediaType.HTML_UTF_8).build();
        final HttpResponse res;
        if (msg == null) {
            res = HttpResponse.of(headers);
        } else {
            res = HttpResponse.of(headers, HttpData.ofUtf8(msg));
        }
        resFuture.complete(res);
    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, null);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        if (resFuture.isDone()) {
            throw new IllegalStateException("response already sent");
        }
        requireNonNull(location, "location");
        resFuture.complete(HttpResponse.of(
                ResponseHeaders.of(HttpStatus.FOUND, HttpHeaderNames.LOCATION, location)));
    }

    @Override
    public void setDateHeader(String name, long date) {
        requireNonNull(name, "name");
        checkArgument(date > 0, "date: %s (expected: > 0)", date);
        headersBuilder.setLong(name, date);
    }

    @Override
    public void addDateHeader(String name, long date) {
        requireNonNull(name, "name");
        checkArgument(date > 0, "date: %s (expected: > 0)", date);
        headersBuilder.addLong(name, date);
    }

    @Override
    public void setHeader(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder.set(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder.add(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        requireNonNull(name, "name");
        headersBuilder.setInt(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        requireNonNull(name, "name");
        headersBuilder.addInt(name, value);
    }

    @Override
    public void setContentType(String contentType) {
        requireNonNull(contentType, "contentType");
        headersBuilder.set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    @Override
    @Nullable
    public String getContentType() {
        return headersBuilder.get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    public void setStatus(int sc) {
        // Should validate sc.
        headersBuilder.status(sc);
    }

    @Override
    @Deprecated
    public void setStatus(int sc, @Nullable String sm) {
        if (sm == null) {
            headersBuilder.status(sc);
        } else {
            headersBuilder.status(new HttpStatus(sc, sm));
        }
    }

    @Override
    public int getStatus() {
        return headersBuilder.status().code();
    }

    @Override
    @Nullable
    public String getHeader(String name) {
        requireNonNull(name, "name");
        return headersBuilder.get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        requireNonNull(name, "name");
        return headersBuilder.getAll(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headersBuilder.names().stream()
                             .map(AsciiString::toString).collect(ImmutableList.toImmutableList());
    }

    @Override
    public String getCharacterEncoding() {
        final MediaType mediaType = headersBuilder.contentType();
        if (mediaType != null && mediaType.charset() != null) {
            return mediaType.charset().toString();
        }
        return servletContext.getResponseCharacterEncoding();
    }

    @Override
    public DefaultServletOutputStream getOutputStream() throws IOException {
        if (usingWriter) {
            throw new IllegalStateException("getWriter() has already call before.");
        }
        usingOutputStream = true;
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (usingOutputStream) {
            throw new IllegalStateException("getOutputStream() has already call before.");
        }
        usingWriter = true;
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        requireNonNull(charset, "charset");
        final MediaType mediaType = headersBuilder.contentType();
        if (mediaType != null) {
            headersBuilder.contentType(mediaType.withCharset(Charset.forName(charset)));
        } else {
            throw new IllegalStateException("Must set content type before setting a charset.");
        }
    }

    @Override
    public void setContentLength(int len) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setContentLengthLong(long len) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBufferSize(int size) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getBufferSize() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void flushBuffer() throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isCommitted() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void resetBuffer() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setLocale(Locale locale) {
        requireNonNull(locale, "locale");
        headersBuilder.set(HttpHeaderNames.CONTENT_LANGUAGE, locale.toLanguageTag());
    }

    @Override
    @Nullable
    public Locale getLocale() {
        final String headerValue = headersBuilder.get(HttpHeaderNames.CONTENT_LANGUAGE);
        if (headerValue == null) {
            return Locale.ENGLISH;
        } else {
            return Arrays.stream(headerValue.split(","))
                         .map(x -> x.split(";").length > 0 ?
                                   Locale.forLanguageTag(x.split(";")[0].trim())
                                                           : Locale.forLanguageTag(x.trim())
                         ).toArray(Locale[]::new)[0];
        }
    }
}
