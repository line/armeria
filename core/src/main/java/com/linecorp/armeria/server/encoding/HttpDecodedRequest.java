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
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link FilteredHttpRequest} that applies HTTP decoding to {@link HttpObject}s as they are published.
 */
final class HttpDecodedRequest extends FilteredHttpRequest {

    private final StreamDecoder responseDecoder;

    HttpDecodedRequest(HttpRequest delegate, StreamDecoderFactory decoderFactory,
                       ByteBufAllocator alloc) {
        super(delegate);
        responseDecoder = decoderFactory.newDecoder(alloc);
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpData) {
            return responseDecoder.decode((HttpData) obj);
        } else {
            return obj;
        }
    }

    @Override
    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
        final HttpData lastData = responseDecoder.finish();
        if (!lastData.isEmpty()) {
            subscriber.onNext(lastData);
        }
    }

    @Override
    protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
        if (cause instanceof CancelledSubscriptionException) {
            // We already handle it in beforeCancel().
            return cause;
        }
        responseDecoder.finish();
        return cause;
    }

    @Override
    protected void beforeCancel(Subscriber<? super HttpObject> subscriber, Subscription subscription) {
        responseDecoder.finish();
    }
}
