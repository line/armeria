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
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;

/**
 * Servlet response.
 */
final class DefaultServletHttpResponse implements HttpServletResponse {
    private final List<Cookie> cookies = new ArrayList<>();
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
        headersBuilder.set(HttpHeaderNames.CONTENT_TYPE, MediaType.HTML_UTF_8.toString());
        setCharacterEncoding(servletContext.getRequestCharacterEncoding());
    }

    /**
     * Get response writer.
     */
    HttpResponseWriter getResponseWriter() {
        return responseWriter;
    }

    /**
     * Get header builder.
     */
    ResponseHeadersBuilder getHeadersBuilder() {
        return headersBuilder;
    }

    /**
     * Get cookie.
     */
    List<Cookie> getCookies() {
        return cookies;
    }

    @Override
    public void addCookie(Cookie cookie) {
        requireNonNull(cookie, "cookie");
        cookies.add(cookie);
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
        if (msg == null) {
            msg = "";
        }
        headersBuilder.status(new HttpStatus(sc, msg));
        responseWriter.tryWrite(headersBuilder.build());
        responseWriter.tryWrite(HttpData.ofUtf8(msg));
        responseWriter.close();
    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, null);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        requireNonNull(location, "location");
        responseWriter.tryWrite(
                ResponseHeaders.of(HttpStatus.SEE_OTHER, HttpHeaderNames.LOCATION.toString(), location));
        responseWriter.close();
    }

    @Override
    public void setDateHeader(String name, long date) {
        requireNonNull(name, "name");
        checkArgument(date > 0, "date: %s (expected: > 0)", date);
        headersBuilder.setObject(name, date);
    }

    @Override
    public void addDateHeader(String name, long date) {
        requireNonNull(name, "name");
        checkArgument(date > 0, "date: %s (expected: > 0)", date);
        headersBuilder.addObject(name, date);
    }

    @Override
    public void setHeader(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder.setObject(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder.addObject(name, value);
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
        setHeader(HttpHeaderNames.CONTENT_TYPE.toString(), contentType);
    }

    @Override
    public String getContentType() {
        final String contentType = getHeader(HttpHeaderNames.CONTENT_TYPE.toString());
        return contentType == null ? "" : contentType;
    }

    @Override
    public void setStatus(int sc) {
        headersBuilder.status(sc);
    }

    @Override
    @Deprecated
    public void setStatus(int sc, @Nullable String sm) {
        if (sm == null) {
            sm = "";
        }
        headersBuilder.status(new HttpStatus(sc, sm));
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
        return headersBuilder.names().stream().map(s -> s.toString()).collect(ImmutableList.toImmutableList());
    }

    @Override
    public String getCharacterEncoding() {
        return getContentType().split(";")[1].trim();
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
        headersBuilder.set(HttpHeaderNames.CONTENT_TYPE,
                           getContentType().split(";")[0].trim() + "; " + charset);
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

    /**
     * Reset response (full reset, head, state, response buffer).
     */
    @Override
    public void reset() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Reset response (only reset response buffer).
     */
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
