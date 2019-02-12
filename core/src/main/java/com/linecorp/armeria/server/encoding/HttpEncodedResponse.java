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

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Predicate;
import java.util.zip.DeflaterOutputStream;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.FilteredStreamMessage;

/**
 * A {@link FilteredStreamMessage} that applies HTTP encoding to {@link HttpObject}s as they are published.
 */
class HttpEncodedResponse extends FilteredHttpResponse {

    private final HttpEncodingType encodingType;
    private final Predicate<MediaType> encodableContentTypePredicate;
    private final long minBytesToForceChunkedAndEncoding;

    @Nullable
    private ByteArrayOutputStream encodedStream;

    @Nullable
    private DeflaterOutputStream encodingStream;

    private boolean headersSent;

    HttpEncodedResponse(
            HttpResponse delegate,
            HttpEncodingType encodingType,
            Predicate<MediaType> encodableContentTypePredicate,
            long minBytesToForceChunkedAndEncoding) {
        super(delegate);
        this.encodingType = requireNonNull(encodingType, "encodingType");
        this.encodableContentTypePredicate = requireNonNull(encodableContentTypePredicate,
                                                            "encodableContentTypePredicate");
        this.minBytesToForceChunkedAndEncoding = HttpEncodingService.validateMinBytesToForceChunkedAndEncoding(
                minBytesToForceChunkedAndEncoding);
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) obj;

            // Skip informational headers.
            final HttpStatus status = headers.status();
            if (status != null && status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                return obj;
            }

            if (headersSent) {
                // Trailing headers, no modification.
                return obj;
            }

            if (status == null) {
                // Follow-up headers for informational headers, no modification.
                return obj;
            }

            headersSent = true;
            if (!shouldEncodeResponse(headers)) {
                return obj;
            }

            encodedStream = new ByteArrayOutputStream();
            encodingStream = HttpEncoders.getEncodingOutputStream(encodingType, encodedStream);

            final HttpHeaders mutable = headers.toMutable();
            // Always use chunked encoding when compressing.
            mutable.remove(HttpHeaderNames.CONTENT_LENGTH);
            switch (encodingType) {
                case GZIP:
                    mutable.set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
                    break;
                case DEFLATE:
                    mutable.set(HttpHeaderNames.CONTENT_ENCODING, "deflate");
                    break;
            }
            mutable.set(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING.toString());
            return mutable;
        }

        if (encodingStream == null) {
            // Encoding was disabled for this response.
            return obj;
        }

        final HttpData data = (HttpData) obj;
        assert encodedStream != null;
        try {
            encodingStream.write(data.array(), data.offset(), data.length());
            encodingStream.flush();
            return HttpData.of(encodedStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Error encoding HttpData, this should not happen with byte arrays.",
                    e);
        } finally {
            encodedStream.reset();
        }
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        closeEncoder();
        if (encodedStream != null && encodedStream.size() > 0) {
            subscriber.onNext(HttpData.of(encodedStream.toByteArray()));
        }
    }

    @Override
    protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        closeEncoder();
        return cause;
    }

    private void closeEncoder() {
        if (encodingStream == null) {
            return;
        }
        try {
            encodingStream.close();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Error closing encodingStream, this should not happen with byte arrays.",
                    e);
        }
    }

    private boolean shouldEncodeResponse(HttpHeaders headers) {
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
        if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // We switch to chunked encoding and compress the response if it's reasonably
            // large as the compression savings should outweigh the chunked encoding
            // overhead.
            if (headers.getInt(HttpHeaderNames.CONTENT_LENGTH) < minBytesToForceChunkedAndEncoding) {
                return false;
            }
        }
        return true;
    }
}
