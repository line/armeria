/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.encoding;

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Predicate;
import java.util.zip.DeflaterOutputStream;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.http.FilteredHttpResponse;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpObject;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatusClass;
import com.linecorp.armeria.common.stream.FilteredStreamMessage;

/**
 * A {@link FilteredStreamMessage} that applies HTTP encoding to {@link HttpObject}s as they are published.
 */
class HttpEncodedResponse extends FilteredHttpResponse {

    private final HttpEncodingType encodingType;
    private final Predicate<MediaType> encodableContentTypePredicate;
    private final int minBytesToForceChunkedAndEncoding;

    @Nullable
    private ByteArrayOutputStream encodedStream;

    @Nullable
    private DeflaterOutputStream encodingStream;

    private boolean headersSent;

    HttpEncodedResponse(
            HttpResponse delegate,
            HttpEncodingType encodingType,
            Predicate<MediaType> encodableContentTypePredicate,
            int minBytesToForceChunkedAndEncoding) {
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
            HttpHeaders headers = (HttpHeaders) obj;

            // Skip informational headers.
            if (headers.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                return obj;
            }

            if (headersSent) {
                // Trailing headers, no modification.
                return obj;
            }
            headersSent = true;
            if (!shouldEncodeResponse(headers)) {
                return obj;
            }

            encodedStream = new ByteArrayOutputStream();
            encodingStream = HttpEncoders.getEncodingOutputStream(encodingType, encodedStream);

            // Always use chunked encoding when compressing.
            headers.remove(HttpHeaderNames.CONTENT_LENGTH);
            switch (encodingType) {
                case GZIP:
                    headers.set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
                    break;
                case DEFLATE:
                    headers.set(HttpHeaderNames.CONTENT_ENCODING, "deflate");
                    break;
            }
            headers.set(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING.toString());
            return headers;
        }

        if (encodingStream == null) {
            // Encoding was disabled for this response.
            return obj;
        }

        HttpData data = (HttpData) obj;
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
    protected void beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        closeEncoder();
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
        if (headers.contains(HttpHeaderNames.CONTENT_TYPE)) {
            // Make sure the content type is worth encoding.
            try {
                MediaType contentType = MediaType.parse(headers.get(HttpHeaderNames.CONTENT_TYPE));
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
