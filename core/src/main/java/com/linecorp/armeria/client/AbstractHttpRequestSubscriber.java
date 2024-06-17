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

package com.linecorp.armeria.client;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;

import io.netty.channel.Channel;

abstract class AbstractHttpRequestSubscriber extends AbstractHttpRequestHandler
        implements Subscriber<HttpObject> {

    private static final HttpData EMPTY_EOS = HttpData.empty().withEndOfStream();

    static AbstractHttpRequestSubscriber of(Channel channel, ClientHttpObjectEncoder requestEncoder,
                                            HttpResponseDecoder responseDecoder, SessionProtocol protocol,
                                            ClientRequestContext ctx, HttpRequest req,
                                            DecodedHttpResponse res, long writeTimeoutMillis,
                                            boolean webSocket) {
        if (webSocket) {
            if (protocol.isExplicitHttp1()) {
                return new WebSocketHttp1RequestSubscriber(
                        channel, requestEncoder, responseDecoder, req, res, ctx, writeTimeoutMillis);
            }
            assert protocol.isExplicitHttp2();
            return new WebSocketHttp2RequestSubscriber(
                    channel, requestEncoder, responseDecoder, req, res, ctx, writeTimeoutMillis);
        }
        return new HttpRequestSubscriber(
                channel, requestEncoder, responseDecoder, req, res, ctx, writeTimeoutMillis);
    }

    private final HttpRequest request;
    private final boolean http1WebSocket;

    @Nullable
    private Subscription subscription;
    private boolean isSubscriptionCompleted;

    AbstractHttpRequestSubscriber(Channel ch, ClientHttpObjectEncoder encoder,
                                  HttpResponseDecoder responseDecoder,
                                  HttpRequest request, DecodedHttpResponse originalRes,
                                  ClientRequestContext ctx, long timeoutMillis, boolean allowTrailers,
                                  boolean keepAlive, boolean http1WebSocket) {
        super(ch, encoder, responseDecoder, originalRes, ctx, timeoutMillis, request.isEmpty(), allowTrailers,
              keepAlive);
        this.request = request;
        this.http1WebSocket = http1WebSocket;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;
        if (state() == State.DONE) {
            cancel();
            return;
        }

        final RequestHeaders headers = mergedRequestHeaders(mapHeaders(request.headers()));
        final boolean needs100Continue = needs100Continue(headers);
        if (needs100Continue && http1WebSocket) {
            failRequest(new IllegalArgumentException(
                    "a WebSocket request is not allowed to have Expect: 100-continue header"));
            return;
        }

        if (!tryInitialize()) {
            return;
        }

        // NB: This must be invoked at the end of this method because otherwise the callback methods in this
        //     class can be called before the member fields (subscription, id, responseWrapper and
        //     timeoutFuture) are initialized.
        writeHeaders(headers, needs100Continue(headers));
        channel().flush();
    }

    RequestHeaders mapHeaders(RequestHeaders headers) {
        return headers;
    }

    @Override
    public void onError(Throwable cause) {
        isSubscriptionCompleted = true;
        failRequest(cause);
    }

    @Override
    public void onComplete() {
        isSubscriptionCompleted = true;

        if (state() != State.DONE) {
            writeData(EMPTY_EOS);
            channel().flush();
        }
    }

    @Override
    void onWriteSuccess() {
        if (state() == State.NEEDS_100_CONTINUE) {
            return;
        }
        request();
    }

    private void request() {
        // Request more messages regardless whether the state is DONE. It makes the producer have
        // a chance to produce the last call such as 'onComplete' and 'onError' when there are
        // no more messages it can produce.
        if (!isSubscriptionCompleted) {
            assert subscription != null;
            subscription.request(1);
        }
    }

    @Override
    void cancel() {
        isSubscriptionCompleted = true;
        assert subscription != null;
        subscription.cancel();
    }

    @Override
    final void resume() {
        request();
    }

    @Override
    void discardRequestBody() {
        cancel();
    }
}
