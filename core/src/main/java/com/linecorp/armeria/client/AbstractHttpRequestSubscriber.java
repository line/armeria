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
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderValues;

abstract class AbstractHttpRequestSubscriber extends AbstractHttpRequestHandler
        implements Subscriber<HttpObject> {

    private static final HttpData EMPTY_EOS = HttpData.empty().withEndOfStream();

    private final HttpRequest request;
    // subscription, id and responseWrapper are assigned in onSubscribe()
    @Nullable
    private Subscription subscription;
    private boolean isSubscriptionCompleted;

    AbstractHttpRequestSubscriber(Channel ch, ClientHttpObjectEncoder encoder,
                                  HttpResponseDecoder responseDecoder,
                                  HttpRequest request, DecodedHttpResponse originalRes,
                                  ClientRequestContext ctx, long timeoutMillis) {
        super(ch, encoder, responseDecoder, originalRes, ctx, timeoutMillis,
              request.isEmpty());
        this.request = request;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;
        if (state() == State.DONE) {
            cancel();
            return;
        }

        if (!tryInitialize()) {
            return;
        }

        // NB: This must be invoked at the end of this method because otherwise the callback methods in this
        //     class can be called before the member fields (subscription, id, responseWrapper and
        //     timeoutFuture) are initialized.
        //     It is because the successful write of the first headers will trigger subscription.request(1).
        RequestHeaders headers = request.headers();
        final HttpSession session = HttpSession.get(channel());
        final SerializationFormat serializationFormat = session.serializationFormat();
        if (serializationFormat == SerializationFormat.WS) {
            final SessionProtocol protocol = session.protocol();
            assert protocol != null;
            if (protocol.isExplicitHttp2()) {
                headers = headers.toBuilder()
                                 .method(HttpMethod.CONNECT)
                                 .removeAndThen(HttpHeaderNames.CONNECTION)
                                 .removeAndThen(HttpHeaderNames.UPGRADE)
                                 .removeAndThen(HttpHeaderNames.SEC_WEBSOCKET_KEY)
                                 .set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString())
                                 .build();
            }
        }
        writeHeaders(headers);
        channel().flush();
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
}