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

package com.linecorp.armeria.server.decoding;

import java.util.Map;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;

import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link FilteredHttpRequest} that applies HTTP decoding to {@link HttpObject}s as they are published.
 */
final class HttpDecodedRequest extends FilteredHttpRequest {

    private final Map<String, StreamDecoderFactory> availableDecoders;
    private final ByteBufAllocator alloc;

    @Nullable
    private StreamDecoder responseDecoder;

    private boolean initializedDecoder;

    HttpDecodedRequest(HttpRequest delegate, Map<String, StreamDecoderFactory> availableDecoders,
                       ByteBufAllocator alloc) {
        super(delegate);
        this.availableDecoders = availableDecoders;
        this.alloc = alloc;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpData) {
            if (!initializedDecoder) {
                initializedDecoder = true;
                final String contentEncoding = headers().get(HttpHeaderNames.CONTENT_ENCODING);
                if (contentEncoding != null) {
                    final StreamDecoderFactory decoderFactory =
                            availableDecoders.get(Ascii.toLowerCase(contentEncoding));
                    // If the client sent an encoding we don't support, decoding will be skipped which is ok.
                    if (decoderFactory != null) {
                        responseDecoder = decoderFactory.newDecoder(alloc);
                    }
                }
            }
            return responseDecoder != null ? responseDecoder.decode((HttpData) obj) : obj;
        } else {
            return obj;
        }
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        if (responseDecoder == null) {
            return;
        }
        final HttpData lastData = responseDecoder.finish();
        if (!lastData.isEmpty()) {
            subscriber.onNext(lastData);
        }
    }

    @Override
    protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        if (responseDecoder != null) {
            responseDecoder.finish();
        }
        return cause;
    }
}
