/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;

import org.jboss.resteasy.specimpl.MultivaluedMapImpl;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.resteasy.ByteBufferBackedOutputStream;

import io.netty.buffer.ByteBuf;

/**
 * Implements {@link org.jboss.resteasy.spi.HttpResponse}.
 */
final class ResteasyHttpResponseImpl implements org.jboss.resteasy.spi.HttpResponse {

    private final MultivaluedMap<String, Object> headers = new MultivaluedMapImpl<>();
    private final Collection<SetCookie> cookies = new LinkedList<>();
    private final ByteBufferBackedOutputStream contentStream;
    private OutputStream contentStreamProxy;
    @Nullable
    private HttpResponseWriter responseWriter;
    @Nullable
    private String errorMessage;
    @Nullable
    private HttpStatus status = HttpStatus.NO_CONTENT;
    private boolean committed;
    private final CompletableFuture<HttpResponse> responseFuture;

    ResteasyHttpResponseImpl(CompletableFuture<HttpResponse> responseFuture, int bufferSize) {
        this.responseFuture = requireNonNull(responseFuture, "responseFuture");
        contentStream = new ByteBufferBackedOutputStream(bufferSize, this::onDataFlush);
        contentStreamProxy = contentStream;
    }

    private HttpStatus httpStatus() {
        return status == null ? !contentStream.hasFlushed() &&
                                !contentStream.hasWritten() ? HttpStatus.NO_CONTENT
                                                            : HttpStatus.OK
                              : status;
    }

    @Override
    public int getStatus() {
        return httpStatus().code();
    }

    @Override
    public void setStatus(int status) {
        if (committed) {
            throw new IllegalStateException("Already committed");
        }
        this.status = HttpStatus.valueOf(status);
    }

    @Override
    public MultivaluedMap<String, Object> getOutputHeaders() {
        return headers;
    }

    @Override
    @Nullable
    public OutputStream getOutputStream() throws IOException {
        return contentStreamProxy;
    }

    @Override
    public void setOutputStream(OutputStream os) {
        contentStreamProxy = os;
    }

    @Override
    public void addNewCookie(NewCookie cookie) {
        if (committed) {
            throw new IllegalStateException("Already committed");
        }
        cookies.add(SetCookie.of(cookie));
    }

    @Override
    public void sendError(int status) {
        sendError(status, null);
    }

    @Override
    public void sendError(int status, @Nullable String message) {
        if (committed) {
            throw new IllegalStateException("Already committed");
        }
        final HttpStatus errorStatus = HttpStatus.valueOf(status);
        checkArgument(errorStatus.isError(), "Not an error status: %s", status);
        this.status = errorStatus;
        errorMessage = message;
        committed = true;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void reset() {
        if (committed) {
            throw new IllegalStateException("Already committed");
        }
        status = HttpStatus.NO_CONTENT;
        headers.clear();
        cookies.clear();
        contentStream.reset();
        responseWriter = null;
        errorMessage = null;
    }

    @Override
    public void flushBuffer() throws IOException {
        contentStream.flush();
    }

    public void finish() {
        responseFuture.complete(completeResponse());
    }

    private void onDataFlush(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            // response stream will be open only if there is something to write to it
            return;
        }
        if (responseWriter == null) {
            // create response stream, write headers adn mark response 'committed'
            responseWriter = HttpResponse.streaming();
            final ResponseHeaders responseHeaders = responseHeaders();
            responseWriter.write(responseHeaders);
            committed = true; // response is irreversible from this point on
        }
        responseWriter.write(HttpData.wrap(buffer));
    }

    private HttpResponse completeResponse() {
        try {
            if (responseWriter != null) {
                // response stream has been open, let's flush the remaining bytes
                if (contentStream.hasWritten()) {
                    contentStream.flush();
                }
                responseWriter.close();
                return responseWriter;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // response stream has not yet been open, lets send the data right away
        final HttpData contentData;
        if (errorMessage != null) {
            contentData = HttpData.ofUtf8(errorMessage);
        } else if (contentStream.hasWritten()) {
            final ByteBuf writtenBytes = contentStream.dumpWrittenAndClose();
            contentData = HttpData.wrap(writtenBytes);
        } else {
            contentData = HttpData.empty();
        }
        final ResponseHeaders responseHeaders = responseHeaders();
        final HttpResponse response =
                contentData.isEmpty() ? HttpResponse.of(responseHeaders)
                                      : HttpResponse.of(responseHeaders, contentData);
        committed = true;
        return response;
    }

    private ResponseHeaders responseHeaders() {
        final ResponseHeadersBuilder headersBuilder = responseHeadersBuilder(httpStatus(), headers);
        cookies.forEach(c -> c.addHeader(headersBuilder));
        return headersBuilder.build();
    }

    private static ResponseHeadersBuilder responseHeadersBuilder(HttpStatus status,
                                                                 MultivaluedMap<String, Object> headers) {
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(status);
        headers.forEach(builder::addObject);
        return builder;
    }
}
