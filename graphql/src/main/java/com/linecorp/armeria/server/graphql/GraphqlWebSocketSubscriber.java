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

package com.linecorp.armeria.server.graphql;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;

@SuppressWarnings("ReactiveStreamsSubscriberImplementation")
final class GraphqlWebSocketSubscriber implements Subscriber<WebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(GraphqlWebSocketSubscriber.class);

    private final GraphqlWSSubProtocol graphqlWSSubProtocol;
    private final WebSocketWriter outgoing;
    @Nullable
    private Subscription subscription;

    GraphqlWebSocketSubscriber(GraphqlWSSubProtocol graphqlWSSubProtocol, WebSocketWriter outgoing) {
        this.graphqlWSSubProtocol = graphqlWSSubProtocol;
        this.outgoing = outgoing;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(1);
    }

    /**
     * Calling onSubscribe, onNext, onError or onComplete MUST return normally except when any provided
     * parameter is null in which case it MUST throw a java.lang.NullPointerException to the caller, for all
     * other situations the only legal way for a Subscriber to signal failure is by cancelling its
     * Subscription. In the case that this rule is violated, any associated Subscription to the Subscriber
     * MUST be considered as cancelled, and the caller MUST raise this error condition in a fashion that is
     * adequate for the runtime environment.
     */
    @Override
    public void onNext(WebSocketFrame webSocketFrame) {
        logger.trace("onNext: {}", webSocketFrame);
        assert subscription != null;
        switch (webSocketFrame.type()) {
            case BINARY:
                graphqlWSSubProtocol.handleBinary(outgoing);
                break;
            case TEXT:
                // Parse the graphql-ws sub protocol. Maybe this could be done in a different thread so not
                // to block the publisher?
                graphqlWSSubProtocol.handleText(webSocketFrame.text(), outgoing);
                /*
                It is RECOMMENDED that Subscribers request the upper limit of what they are able to process,
                as requesting only one element at a time results in an inherently
                inefficient "stop-and-wait" protocol.
                 */
                subscription.request(1);
                break;
            case PING:
                outgoing.writePong();
                subscription.request(1);
                break;
            case CLOSE:
                outgoing.close();
                break;
            // PONG is a noop
            case PONG:
                subscription.request(1);
                break;
            // Continuation is not mentioned in the spec. Should never happen.
            case CONTINUATION:
                logger.trace("Ignoring frame type: {}", webSocketFrame.type());
                subscription.request(1);
                break;
            default:
                // Should never reach here.
                throw new Error();
        }
    }

    @Override
    public void onError(Throwable t) {
        /*
        Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider
        the Subscription cancelled after having received the signal.
         */
        logger.trace("onError", t);
        graphqlWSSubProtocol.cancel();
        subscription = null;
    }

    @Override
    public void onComplete() {
        /*
        Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider
        the Subscription cancelled after having received the signal.
         */
        logger.trace("onComplete");
        graphqlWSSubProtocol.cancel();
        subscription = null;
    }
}
