/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.encoding;

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Predicate;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.FilteredStreamMessage;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;

/**
 * A {@link FilteredStreamMessage} that applies HTTP encoding to {@link HttpObject}s as they are published.
 */
final class HttpEncodedResponse extends FilteredHttpResponse {

    private static final Logger logger = LoggerFactory.getLogger(HttpEncodedResponse.class);

    private final StreamEncoderFactory encoderFactory;
    private final Predicate<MediaType> encodableContentTypePredicate;
    private final long minBytesToForceChunkedAndEncoding;
    private final ByteBufAllocator alloc;

    @VisibleForTesting
    @Nullable
    ByteBufOutputStream encodedStream;

    @Nullable
    private OutputStream encodingStream;

    private boolean headersSent;

    private boolean encoderClosed;

    HttpEncodedResponse(HttpResponse delegate,
                        StreamEncoderFactory encoderFactory,
                        Predicate<MediaType> encodableContentTypePredicate,
                        ByteBufAllocator alloc,
                        long minBytesToForceChunkedAndEncoding) {
        super(delegate);
        this.encoderFactory = encoderFactory;
        this.encodableContentTypePredicate = encodableContentTypePredicate;
        this.alloc = alloc;
        this.minBytesToForceChunkedAndEncoding = minBytesToForceChunkedAndEncoding;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof ResponseHeaders) {
            final ResponseHeaders headers = (ResponseHeaders) obj;

            // Skip informational headers.
            final HttpStatus status = headers.status();
            if (status.isInformational()) {
                return obj;
            }

            if (headersSent) {
                // Trailers, no modification.
                return obj;
            }

            headersSent = true;
            if (!shouldEncodeResponse(headers)) {
                return obj;
            }

            final ByteBuf buf;
            final long contentLength = headers.contentLength();
            if (contentLength > 0) {
                // A compression ratio heavily depends on the content but the compression ratio is higher than
                // 50% in common cases.
                buf = alloc.buffer(Ints.saturatedCast(contentLength) / 2);
            } else {
                buf = alloc.buffer();
            }
            encodedStream = new ByteBufOutputStream(buf);
            encodingStream = encoderFactory.newEncoder(encodedStream);

            final ResponseHeadersBuilder mutable = headers.toBuilder();
            // Always use chunked encoding when compressing.
            mutable.remove(HttpHeaderNames.CONTENT_LENGTH);
            mutable.set(HttpHeaderNames.CONTENT_ENCODING, encoderFactory.encodingHeaderValue());
            mutable.set(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING.toString());
            return mutable.build();
        }

        if (obj instanceof HttpHeaders) {
            // Trailers.
            return obj;
        }

        if (encodingStream == null) {
            // Encoding was disabled for this response.
            return obj;
        }

        final HttpData data = (HttpData) obj;
        assert encodedStream != null;
        try {
            encodingStream.write(data.array());
            encodingStream.flush();
            final ByteBuf encodedBuf = encodedStream.buffer();
            final HttpData httpData = HttpData.wrap(encodedBuf.retainedSlice());
            encodedBuf.readerIndex(encodedBuf.writerIndex());
            return httpData;
        } catch (IOException e) {
            // An unreleased ByteBuf will be released by `beforeError()`
            throw new IllegalStateException(
                    "Error encoding HttpData, this should not happen with byte arrays.",
                    e);
        }
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        closeEncoder(false);
        if (encodedStream == null) {
            return;
        }

        final ByteBuf buf = encodedStream.buffer();
        if (buf.isReadable()) {
            try {
                subscriber.onNext(HttpData.wrap(buf));
            } catch (Throwable t) {
                subscriber.onError(t);
                throwIfFatal(t);
                logger.warn("Subscriber.onNext() should not raise an exception. subscriber: {}",
                            subscriber, t);
            }
        } else {
            buf.release();
        }
    }

    @Override
    protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        closeEncoder(true);
        return cause;
    }

    @Override
    protected void onCancellation(Subscriber<? super HttpObject> subscriber) {
        closeEncoder(true);
    }

    private void closeEncoder(boolean releaseEncodedBuf) {
        if (encoderClosed) {
            return;
        }
        encoderClosed = true;
        if (encodingStream == null) {
            return;
        }
        try {
            encodingStream.close();
            if (encodedStream != null && releaseEncodedBuf) {
                encodedStream.buffer().release();
            }
        } catch (IOException e) {
            logger.warn("Unexpected exception is raised while closing the encoding stream.", e);
        }
    }

    private boolean shouldEncodeResponse(ResponseHeaders headers) {
        if (headers.status().isContentAlwaysEmpty()) {
            return false;
        }
        if (headers.contains(HttpHeaderNames.CONTENT_ENCODING)) {
            // We don't do automatic encoding if the user-supplied headers contain
            // Content-Encoding.
            return false;
        }
        if (headers.contentType() != null) {
            // Make sure the content type is worth encoding.
            try {
                final MediaType contentType = headers.contentType();
                if (!encodableContentTypePredicate.test(contentType)) {
                    return false;
                }
            } catch (IllegalArgumentException e) {
                // Don't know content type of response, don't encode.
                return false;
            }
        }

        // We switch to chunked encoding and compress the response if it's reasonably
        // large or the content length is unknown because the compression savings should
        // outweigh the chunked encoding overhead.
        long contentLength = headers.contentLength();
        if (contentLength == -1) {
            contentLength = Long.MAX_VALUE;
        }
        return contentLength >= minBytesToForceChunkedAndEncoding;
    }
}
