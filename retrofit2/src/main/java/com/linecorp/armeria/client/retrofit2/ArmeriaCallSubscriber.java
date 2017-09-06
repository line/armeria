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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatusClass;

import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

final class ArmeriaCallSubscriber implements Subscriber<HttpObject> {
    private final ArmeriaCall armeriaCall;
    private final Callback callback;
    private final Request request;
    private final Response.Builder responseBuilder = new Response.Builder();
    private final Buffer responseDataBuffer = new Buffer();
    private Subscription subscription;
    private HttpHeaders headers;
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
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(HttpObject httpObject) {
        if (armeriaCall.isCanceled()) {
            safeOnFailure(newCanceledException());
            subscription.cancel();
            return;
        }
        if (httpObject instanceof HttpHeaders) {
            if (((HttpHeaders) httpObject).status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                return;
            }
            if (headers != null) {
                return;
            }

            headers = (HttpHeaders) httpObject;
            String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
            headers.forEach(header -> responseBuilder.addHeader(header.getKey().toString(),
                                                                header.getValue()));
            responseBuilder.code(headers.status().code());
            responseBuilder.message(headers.status().reasonPhrase());
            responseBuilder.body(ResponseBody.create(
                    Strings.isNullOrEmpty(contentType) ? null : MediaType.parse(contentType),
                    headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1L),
                    responseDataBuffer));
            return;
        }
        HttpData data = (HttpData) httpObject;
        responseDataBuffer.write(data.array(), data.offset(), data.length());
    }

    @Override
    public void onError(Throwable throwable) {
        if (armeriaCall.tryFinish()) {
            safeOnFailure(new IOException(throwable.getMessage(), throwable));
        } else {
            safeOnFailure(newCanceledException());
        }
    }

    @Override
    public void onComplete() {
        if (armeriaCall.tryFinish()) {
            responseBuilder.request(request);
            responseBuilder.protocol(Protocol.HTTP_1_1);
            safeOnResponse(responseBuilder.build());
        } else {
            safeOnFailure(newCanceledException());
        }
    }

    private void safeOnFailure(IOException e) {
        if (callbackCalled) {
            return;
        }
        callbackCalled = true;
        callback.onFailure(armeriaCall, e);
    }

    private void safeOnResponse(Response response) {
        if (callbackCalled) {
            return;
        }
        callbackCalled = true;
        try {
            callback.onResponse(armeriaCall, response);
        } catch (IOException e) {
            callback.onFailure(armeriaCall, e);
        }
    }

    private static IOException newCanceledException() {
        return new IOException("Canceled");
    }
}
