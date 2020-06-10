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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.util.AsciiString;

/**
 * Servlet response.
 */
final class DefaultServletHttpResponse implements HttpServletResponse {
    private final List<String> cookies = new ArrayList<>();
    private final DefaultServletOutputStream outputStream;
    private final ResponseHeadersBuilder headersBuilder = ResponseHeaders.builder();
    private final PrintWriter writer;
    private final HttpResponseWriter responseWriter;

    DefaultServletHttpResponse(DefaultServletContext servletContext, HttpResponseWriter responseWriter) {
        requireNonNull(servletContext, "servletContext");
        requireNonNull(responseWriter, "responseWriter");
        this.responseWriter = responseWriter;
        outputStream = new DefaultServletOutputStream(this);
        writer = new ServletPrintWriter(this, outputStream);
        headersBuilder.contentType(MediaType.HTML_UTF_8);
        setCharacterEncoding(servletContext.getResponseCharacterEncoding());
    }

    /**
     * Write {@link HttpData} to client.
     */
    void write(HttpData data) {
        requireNonNull(data, "data");
        if (responseWriter.tryWrite(headersBuilder.setObject(HttpHeaderNames.SET_COOKIE, cookies)
                                                  .status(HttpStatus.OK).build())) {
            if (responseWriter.tryWrite(data)) {
                responseWriter.close();
            }
        }
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
        cookies.add(builder.build().toSetCookieHeader());
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
        final ResponseHeaders headers = ResponseHeaders.builder(sc).contentType(MediaType.HTML_UTF_8).build();
        if (responseWriter.tryWrite(headers)) {
            if (msg != null) {
                if (!responseWriter.tryWrite(HttpData.ofUtf8(msg))) {
                    return;
                }
            }
            responseWriter.close();
        }
    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, null);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        requireNonNull(location, "location");
        if (responseWriter.tryWrite(
                ResponseHeaders.of(HttpStatus.FOUND, HttpHeaderNames.LOCATION, location))) {
            responseWriter.close();
        }
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
        checkArgument(value >= 0, "value: %s (expected: >= 0)", value);
        headersBuilder.setInt(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        requireNonNull(name, "name");
        checkArgument(value >= 0, "value: %s (expected: >= 0)", value);
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
        return getHeader(HttpHeaderNames.CONTENT_TYPE.toString());
    }

    @Override
    public void setStatus(int sc) {
        headersBuilder.status(sc);
    }

    @Override
    @Deprecated
    public void setStatus(int sc, @Nullable String sm) {
        if (sm == null) {
            headersBuilder.status(HttpStatus.valueOf(sc));
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
        return ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET.name();
    }

    @Override
    public DefaultServletOutputStream getOutputStream() throws IOException {
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        requireNonNull(charset, "charset");
        final MediaType mediaType = headersBuilder.contentType();
        if (mediaType != null) {
            headersBuilder.contentType(mediaType.withCharset(Charset.forName(charset)));
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
