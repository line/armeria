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

package com.linecorp.armeria.server.encoding;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.util.CompositeException;

import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link FilteredHttpRequest} that applies HTTP decoding to {@link HttpObject}s as they are published.
 */
final class HttpDecodedRequest extends FilteredHttpRequest {

    private static final Logger logger = LoggerFactory.getLogger(HttpDecodedRequest.class);

    private final StreamDecoder requestDecoder;

    private boolean decoderFinished;

    HttpDecodedRequest(HttpRequest delegate, StreamDecoderFactory decoderFactory,
                       ByteBufAllocator alloc, int maxRequestLength) {
        super(delegate);
        requestDecoder = decoderFactory.newDecoder(alloc, maxRequestLength);
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpData) {
            return requestDecoder.decode((HttpData) obj);
        } else {
            return obj;
        }
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        final HttpData lastData = closeRequestDecoder();
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
        try {
            final HttpData lastData = closeRequestDecoder();
            if (lastData != null) {
                lastData.close();
            }
            return cause;
        } catch (Exception decoderException) {
            return new CompositeException(cause, decoderException);
        }
    }

    @Override
    protected void onCancellation(Subscriber<? super HttpObject> subscriber) {
        try {
            final HttpData lastData = closeRequestDecoder();
            if (lastData != null) {
                lastData.close();
            }
        } catch (ContentTooLargeException cause) {
            // Just warn the cause since a stream is being cancelled.
            logger.warn("A request content exceeds the maximum allowed request length. headers: {}",
                        headers(), cause);
        }
    }

    @Nullable
    private HttpData closeRequestDecoder() {
        if (decoderFinished) {
            return null;
        }
        decoderFinished = true;
        return requestDecoder.finish();
    }
}
