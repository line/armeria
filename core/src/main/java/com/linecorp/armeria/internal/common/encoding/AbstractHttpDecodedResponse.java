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

package com.linecorp.armeria.internal.common.encoding;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.encoding.StreamDecoder;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.CompositeException;

abstract class AbstractHttpDecodedResponse extends FilteredHttpResponse {

    private static final Logger logger = LoggerFactory.getLogger(AbstractHttpDecodedResponse.class);

    private boolean decoderClosed;

    AbstractHttpDecodedResponse(HttpResponse delegate) {
        super(delegate, true);
    }

    @Nullable
    abstract StreamDecoder decoder();

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
        try {
            final HttpData lastData = closeResponseDecoder();
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
            final HttpData lastData = closeResponseDecoder();
            if (lastData != null) {
                lastData.close();
            }
        } catch (ContentTooLargeException cause) {
            // Just warn the cause since a stream is being cancelled.
            logger.warn("A response content exceeds the maximum allowed response length.", cause);
        }
    }

    @Nullable
    private HttpData closeResponseDecoder() {
        if (decoderClosed) {
            return null;
        }
        decoderClosed = true;
        if (decoder() == null) {
            return null;
        }
        return decoder().finish();
    }
}
