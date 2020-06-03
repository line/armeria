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

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Formatter;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;

/**
 *  Printing flow.
 */
final class ServletPrintWriter extends PrintWriter {
    private final String lineSeparator = System.lineSeparator();
    private final DefaultServletHttpResponse response;

    private boolean error;

    ServletPrintWriter(DefaultServletHttpResponse response, OutputStream out) {
        super(out);
        requireNonNull(response, "response");
        this.response = response;
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean checkError() {
        return error;
    }

    @Override
    protected void setError() {
        error = true;
    }

    @Override
    protected void clearError() {
        error = false;
    }

    @Override
    public void write(int c) {
        write(String.valueOf(c));
    }

    @Override
    public void write(char[] buf, int off, int len) {
        requireNonNull(buf, "buf");
        checkArgument(off >= 0, "off: %s (expected: >= 0)", off);
        checkArgument(len >= 0, "len: %s (expected: >= 0)", len);
        write(String.valueOf(buf, off, len));
    }

    @Override
    public void write(char[] buf) {
        requireNonNull(buf, "buf");
        write(String.valueOf(buf));
    }

    @Override
    public void write(String s, int off, int len) {
        requireNonNull(s, "s");
        checkArgument(off >= 0, "off: %s (expected: >= 0)", off);
        checkArgument(len >= 0, "len: %s (expected: >= 0)", len);
        final String writeStr;
        if (off == 0 && s.length() == len) {
            writeStr = s;
        } else {
            writeStr = s.substring(off, off + len);
        }
        response.getHeadersBuilder().status(HttpStatus.OK);
        response.getHeadersBuilder().setObject(
                HttpHeaderNames.SET_COOKIE,
                response.getCookies().stream().map(x ->
                                                           Cookie.builder(x.getName(), x.getValue())
                                                                 .path("/")
                                                                 .httpOnly(true)
                                                                 .build().toSetCookieHeader()
                ).collect(Collectors.toList()));
        response.getResponseWriter().tryWrite(response.getHeadersBuilder().build());
        response.getResponseWriter().tryWrite(HttpData.ofUtf8(writeStr));
        response.getResponseWriter().close();
    }

    @Override
    public void write(String s) {
        requireNonNull(s, "s");
        write(s, 0, s.length());
    }

    @Override
    public void print(boolean b) {
        write(b ? "true" : "false");
    }

    @Override
    public void print(char c) {
        write(String.valueOf(c));
    }

    @Override
    public void print(int i) {
        write(String.valueOf(i));
    }

    @Override
    public void print(long l) {
        write(String.valueOf(l));
    }

    @Override
    public void print(float f) {
        write(String.valueOf(f));
    }

    @Override
    public void print(double d) {
        write(String.valueOf(d));
    }

    @Override
    public void print(char[] s) {
        write(s);
    }

    @Override
    public void print(String s) {
        requireNonNull(s, "s");
        write(s);
    }

    @Override
    public void print(Object obj) {
        requireNonNull(obj, "obj");
        write(String.valueOf(obj));
    }

    @Override
    public void println() {
        write(lineSeparator);
    }

    @Override
    public void println(boolean b) {
        write((b ? "true" : "false") + lineSeparator);
    }

    @Override
    public void println(char x) {
        write(x + lineSeparator);
    }

    @Override
    public void println(int x) {
        write(x + lineSeparator);
    }

    @Override
    public void println(long x) {
        write(x + lineSeparator);
    }

    @Override
    public void println(float x) {
        write(x + lineSeparator);
    }

    @Override
    public void println(double x) {
        write(x + lineSeparator);
    }

    @Override
    public void println(char[] x) {
        write(String.valueOf(x) + lineSeparator);
    }

    @Override
    public void println(String x) {
        requireNonNull(x, "x");
        write(x + lineSeparator);
    }

    @Override
    public void println(Object x) {
        requireNonNull(x, "x");
        write(x + lineSeparator);
    }

    @Override
    public PrintWriter printf(String format, Object... args) {
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        format(Locale.getDefault(), format, args);
        return this;
    }

    @Override
    public PrintWriter printf(Locale l, String format, Object... args) {
        requireNonNull(l, "l");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        format(l, format, args);
        return this;
    }

    @Override
    public PrintWriter format(String format, Object... args) {
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        format(Locale.getDefault(), format, args);
        return this;
    }

    @Override
    public PrintWriter format(Locale l, String format, Object... args) {
        requireNonNull(l, "l");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        final StringBuilder sb = new StringBuilder();
        final Formatter formatter = new Formatter(sb, l);
        formatter.format(l, format, args);
        write(sb.toString());
        return this;
    }

    @Override
    public PrintWriter append(@Nullable CharSequence csq) {
        if (csq == null) {
            write("null");
        } else {
            write(csq.toString());
        }
        return this;
    }

    @Override
    public PrintWriter append(@Nullable CharSequence csq, int start, int end) {
        checkArgument(start >= 0, "start: %s (expected: >= 0)", start);
        checkArgument(end >= 0, "end: %s (expected: >= 0)", end);
        final CharSequence cs = csq == null ? "null" : csq;
        write(cs.subSequence(start, end).toString());
        return this;
    }

    @Override
    public PrintWriter append(char c) {
        write(c);
        return this;
    }
}
