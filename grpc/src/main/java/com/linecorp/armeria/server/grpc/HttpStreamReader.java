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

package com.linecorp.armeria.server.grpc;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpObject;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ErrorListener;

import io.grpc.Status;

/**
 * A {@link Subscriber} to read request data and pass it to a {@link ArmeriaServerCall} for processing.
 */
class HttpStreamReader implements Subscriber<HttpObject> {

    private static final Logger logger = LoggerFactory.getLogger(HttpStreamReader.class);

    private final ErrorListener errorListener;

    @VisibleForTesting
    final ArmeriaMessageDeframer deframer;

    @Nullable
    private Subscription subscription;

    HttpStreamReader(ArmeriaMessageDeframer deframer,
                     ErrorListener errorListener) {
        this.deframer = deframer;
        this.errorListener = errorListener;
    }

    // Must be called from an IO thread.
    public void request(int numMessages) {
        deframer.request(numMessages);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        if (deframer.isStalled()) {
            subscription.request(1);
        }
    }

    @Override
    public void onNext(HttpObject obj) {
        if (obj instanceof HttpHeaders) {
            // GRPC clients never send trailing headers so we should treat this as a bad request.
            logger.info("Trailing headers received from GRPC client, this should never happen: {}.", obj);
            errorListener.onError(Status.ABORTED);
            subscription.cancel();
            return;
        }
        HttpData data = (HttpData) obj;
        try {
            deframer.deframe(data, false);
        } catch (Throwable cause) {
            try {
                errorListener.onError(Status.fromThrowable(cause));
                return;
            } finally {
                deframer.close();
            }
        }
        if (deframer.isStalled()) {
            subscription.request(1);
        }
    }

    @Override
    public void onError(Throwable cause) {
        errorListener.onError(Status.fromThrowable(cause));
    }

    @Override
    public void onComplete() {
        deframer.deframe(HttpData.EMPTY_DATA, true);
        deframer.close();
    }

    void cancel() {
        if (subscription != null) {
            subscription.cancel();
        }
    }
}
