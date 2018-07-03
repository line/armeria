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
package com.linecorp.armeria.client.retrofit2;

import java.io.IOException;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.CommonPools;
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
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;

final class ArmeriaCallSubscriber implements Subscriber<HttpObject> {

    private static final long NO_CONTENT_LENGTH = -1;

    private final ArmeriaCall armeriaCall;
    private final Callback callback;
    private final Request request;
    private final Response.Builder responseBuilder = new Response.Builder();
    private final PipeBuffer pipeBuffer = new PipeBuffer();
    @Nullable
    private Subscription subscription;
    private boolean nonInformationalHeadersStarted;
    @Nullable
    private String contentType;
    private long contentLength = NO_CONTENT_LENGTH;
    private boolean callbackCalled;

    ArmeriaCallSubscriber(ArmeriaCall armeriaCall, Callback callback, Request request) {
        this.armeriaCall = armeriaCall;
        this.callback = callback;
        this.request = request;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        if (armeriaCall.isCanceled()) {
            safeOnFailure(newCanceledException());
            subscription.cancel();
            return;
        }
        subscription.request(1);
    }

    @Override
    public void onNext(HttpObject httpObject) {
        if (armeriaCall.isCanceled()) {
            safeOnFailure(newCanceledException());
            subscription.cancel();
            return;
        }
        if (httpObject instanceof HttpHeaders) {
            subscription.request(1);
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
        if (!callbackCalled) {
            responseBuilder.body(ResponseBody.create(
                    Strings.isNullOrEmpty(contentType) ? null : MediaType.parse(contentType),
                    contentLength, Okio.buffer(new ForwardingSource(pipeBuffer.source()) {
                        @Override
                        public long read(Buffer sink, long byteCount) throws IOException {
                            subscription.request(1);
                            return super.read(sink, byteCount);
                        }
                    })));
            responseBuilder.request(request);
            responseBuilder.protocol(Protocol.HTTP_1_1);
            safeOnResponse(responseBuilder.build());
        }

        final HttpData data = (HttpData) httpObject;
        pipeBuffer.write(data.array(), data.offset(), data.length());
    }

    @Override
    public void onError(Throwable throwable) {
        if (armeriaCall.tryFinish()) {
            safeOnFailure(new IOException(throwable.getMessage(), throwable));
        } else {
            safeOnFailure(newCanceledException());
        }
        pipeBuffer.close(throwable);
    }

    @Override
    public void onComplete() {
        if (!armeriaCall.tryFinish()) {
            safeOnFailure(newCanceledException());
        }
        pipeBuffer.close(null);
    }

    private void safeOnFailure(IOException e) {
        if (callbackCalled) {
            return;
        }
        callbackCalled = true;
        CommonPools.blockingTaskExecutor().execute(() -> callback.onFailure(armeriaCall, e));
    }

    private void safeOnResponse(Response response) {
        if (callbackCalled) {
            return;
        }
        callbackCalled = true;
        CommonPools.blockingTaskExecutor().execute(() -> {
            try {
                callback.onResponse(armeriaCall, response);
            } catch (IOException e) {
                callback.onFailure(armeriaCall, e);
            }
        });
    }

    private static IOException newCanceledException() {
        return new IOException("Canceled");
    }
}
