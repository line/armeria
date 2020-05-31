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
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;

import io.netty.handler.codec.http.HttpConstants;

/**
 * Servlet response.
 */
public class ServletHttpResponse implements HttpServletResponse {
    private final DefaultServletContext servletContext;
    private final List<Cookie> cookies = new ArrayList<>();
    private final DefaultServletOutputStream outputStream = new DefaultServletOutputStream();
    private final ResponseHeadersBuilder headersBuilder = ResponseHeaders.builder();

    private String characterEncoding = HttpConstants.DEFAULT_CHARSET.name();
    private Locale locale = Locale.getDefault();

    @Nullable
    private PrintWriter writer;
    @Nullable
    private String contentType;
    @Nullable
    private HttpResponseWriter responseWriter;

    protected ServletHttpResponse(DefaultServletContext servletContext) {
        requireNonNull(servletContext, "servletContext");
        this.servletContext = servletContext;
    }

    /**
     * Get response writer.
     */
    @Nullable
    public HttpResponseWriter getResponseWriter() {
        return responseWriter;
    }

    /**
     * Get response writer.
     */
    public void setResponseWriter(HttpResponseWriter responseWriter) {
        requireNonNull(responseWriter, "responseWriter");
        this.responseWriter = responseWriter;
    }

    /**
     * Get header builder.
     */
    public ResponseHeadersBuilder getHeadersBuilder() {
        return headersBuilder;
    }

    /**
     * Get cookie.
     */
    @Nullable
    public List<Cookie> getCookies() {
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
        if (responseWriter == null) {
            return;
        }
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
        if (responseWriter == null) {
            return;
        }
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
    public void setContentType(String type) {
        requireNonNull(type, "type");
        contentType = type;
        setHeader(HttpHeaderNames.CONTENT_TYPE.toString(), type);
    }

    @Override
    @Nullable
    public String getContentType() {
        return contentType;
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
        return characterEncoding;
    }

    @Override
    public DefaultServletOutputStream getOutputStream() throws IOException {
        outputStream.setResponse(this);
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer != null) {
            return writer;
        }

        String characterEncoding = getCharacterEncoding();
        if (isNullOrEmpty(characterEncoding)) {
            characterEncoding = servletContext.getResponseCharacterEncoding();
            setCharacterEncoding(characterEncoding);
        }

        writer = new ServletPrintWriter(getOutputStream(), Charset.forName(characterEncoding));
        ((ServletPrintWriter) writer).setResponse(this);
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        requireNonNull(charset, "charset");
        characterEncoding = charset;
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

    /**
     * Whether to reset the print stream.
     * @param resetWriterStreamFlags True = resets the print stream, false= does not reset the print stream.
     */
    public void resetBuffer(boolean resetWriterStreamFlags) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setLocale(Locale loc) {
        requireNonNull(loc, "loc");
        locale = loc;
    }

    @Override
    @Nullable
    public Locale getLocale() {
        return locale;
    }
}
