/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.encoding;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link FilteredHttpResponse} that applies HTTP decoding to {@link HttpObject}s as they are published.
 */
final class HttpDecodedResponse extends FilteredHttpResponse {

    private final Map<String, StreamDecoderFactory> availableDecoders;
    private final ByteBufAllocator alloc;
    private final boolean strictContentEncoding;

    @Nullable
    private StreamDecoder responseDecoder;
    private boolean headersReceived;
    private boolean decoderClosed;

    HttpDecodedResponse(HttpResponse delegate, Map<String, StreamDecoderFactory> availableDecoders,
                        ByteBufAllocator alloc, boolean strictContentEncoding) {
        super(delegate, true);
        this.availableDecoders = availableDecoders;
        this.alloc = alloc;
        this.strictContentEncoding = strictContentEncoding;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) obj;

            // Skip informational headers.
            final String status = headers.get(HttpHeaderNames.STATUS);
            if (ArmeriaHttpUtil.isInformational(status)) {
                return obj;
            }

            if (headersReceived) {
                // Trailers, no modification.
                return obj;
            }

            if (status == null) {
                // Follow-up headers for informational headers, no modification.
                return obj;
            }

            headersReceived = true;

            final String contentEncoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
            if (contentEncoding != null) {
                final StreamDecoderFactory decoderFactory =
                        availableDecoders.get(Ascii.toLowerCase(contentEncoding));
                if (decoderFactory != null) {
                    responseDecoder = decoderFactory.newDecoder(alloc);
                } else {
                    // The server returned an encoding we don't support.
                    // This shouldn't happen normally since we set Accept-Encoding.
                    if (strictContentEncoding) {
                        Exceptions.throwUnsafely(
                                new UnsupportedEncodingException("encoding: " + contentEncoding));
                    } else {
                        // Decoding is skipped.
                    }
                }
            }

            return headers;
        }

        assert obj instanceof HttpData;

        return responseDecoder != null ? responseDecoder.decode((HttpData) obj) : obj;
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        final HttpData lastData = closeResponseDecoder();
        if (lastData == null) {
            return;
        }
        if (!lastData.isEmpty()) {
            subscriber.onNext(lastData);
        } else {
            lastData.close();
        }
    }

    @Override
    protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        final HttpData lastData = closeResponseDecoder();
        if (lastData != null) {
            lastData.close();
        }
        return cause;
    }

    @Override
    protected void onCancellation(Subscriber<? super HttpObject> subscriber) {
        final HttpData lastData = closeResponseDecoder();
        if (lastData != null) {
            lastData.close();
        }
    }

    @Nullable
    private HttpData closeResponseDecoder() {
        if (decoderClosed) {
            return null;
        }
        decoderClosed = true;
        if (responseDecoder == null) {
            return null;
        }
        return responseDecoder.finish();
    }
}
