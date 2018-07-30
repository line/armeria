/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.retrofit2;

import java.io.IOException;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

abstract class AbstractSubscriber implements Subscriber<HttpObject> {
    private static final long NO_CONTENT_LENGTH = -1;
    private final Response.Builder responseBuilder = new Response.Builder();
    private final ArmeriaCall armeriaCall;
    private final Callback callback;
    private final Executor callbackExecutor;
    @Nullable
    private Subscription subscription;
    private boolean callbackCalled;
    private boolean nonInformationalHeadersStarted;
    @Nullable
    private String contentType;
    private long contentLength = NO_CONTENT_LENGTH;

    AbstractSubscriber(ArmeriaCall armeriaCall, Request request, Callback callback, Executor callbackExecutor) {
        this.armeriaCall = armeriaCall;
        this.callback = callback;
        this.callbackExecutor = callbackExecutor;
        responseBuilder.request(request)
                       .protocol(Protocol.HTTP_1_1);
    }

    @Override
    public final void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        if (armeriaCall.isCanceled()) {
            onCancelled();
            subscription.cancel();
            return;
        }
        onSubscribe0();
    }

    @Override
    public final void onNext(HttpObject httpObject) {
        if (armeriaCall.isCanceled()) {
            onCancelled();
            subscription.cancel();
            return;
        }
        if (httpObject instanceof HttpHeaders) {
            onHttpHeaders();
            final HttpHeaders headers = (HttpHeaders) httpObject;
            final HttpStatus status = headers.status();
            if (!nonInformationalHeadersStarted) { // If not received a non-informational header yet
                // Ignore informational headers or the headers without :status.
                if (status == null || status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                    return;
                }
                nonInformationalHeadersStarted = true;
                responseBuilder.code(status.code());
                responseBuilder.message(status.reasonPhrase());
            }

            headers.forEach(header -> responseBuilder.addHeader(header.getKey().toString(),
                                                                header.getValue()));
            if (contentType == null) {
                contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
            }
            if (contentLength == NO_CONTENT_LENGTH) {
                contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, NO_CONTENT_LENGTH);
            }
            return;
        }

        final HttpData data = (HttpData) httpObject;
        onHttpData(data);
    }

    @Override
    public final void onError(Throwable throwable) {
        if (armeriaCall.tryFinish()) {
            onError0(new IOException(throwable.getMessage(), throwable));
        } else {
            onError0(newCancelledException());
        }
    }

    @Override
    public final void onComplete() {
        if (armeriaCall.tryFinish()) {
            onComplete0();
        } else {
            onError0(newCancelledException());
        }
    }

    void request(long n) {
        assert subscription != null;
        subscription.request(n);
    }

    void safeOnFailure(IOException e) {
        if (callbackCalled) {
            return;
        }
        callbackCalled = true;
        callbackExecutor.execute(() -> callback.onFailure(armeriaCall, e));
    }

    void safeOnResponse(BufferedSource content) {
        if (callbackCalled) {
            return;
        }
        callbackCalled = true;
        callbackExecutor.execute(() -> {
            try {
                callback.onResponse(armeriaCall, responseBuilder
                        .body(ResponseBody.create(Strings.isNullOrEmpty(contentType) ?
                                                  null : MediaType.parse(contentType),
                                                  contentLength, content))
                        .build());
            } catch (IOException e) {
                callback.onFailure(armeriaCall, e);
            }
        });
    }

    abstract void onSubscribe0();

    abstract void onCancelled();

    abstract void onHttpHeaders();

    abstract void onHttpData(HttpData data);

    abstract void onError0(IOException e);

    abstract void onComplete0();

    static IOException newCancelledException() {
        return new IOException("cancelled");
    }
}
