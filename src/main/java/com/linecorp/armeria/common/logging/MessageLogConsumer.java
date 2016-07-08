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

package com.linecorp.armeria.common.logging;

import java.util.Objects;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;

public interface MessageLogConsumer {

    static void invokeOnRequest(MessageLogConsumer consumer, RequestContext ctx, RequestLog req) {
        try {
            consumer.onRequest(ctx, req);
        } catch (Throwable e) {
            LoggerFactory.getLogger(MessageLogConsumer.class)
                         .warn("onRequest() failed with an exception: {}", e);
        }
    }

    static void invokeOnResponse(MessageLogConsumer consumer, RequestContext ctx, ResponseLog res) {
        try {
            consumer.onResponse(ctx, res);
        } catch (Throwable e) {
            LoggerFactory.getLogger(MessageLogConsumer.class)
                         .warn("onResponse() failed with an exception: {}", e);
        }
    }

    /**
     * Invoked when a request has been streamed.
     */
    void onRequest(RequestContext ctx, RequestLog req);

    /**
     * Invoked when a response has been streamed.
     */
    void onResponse(RequestContext ctx, ResponseLog res);

    default MessageLogConsumer andThen(MessageLogConsumer other) {
        Objects.requireNonNull(other, "other");

        final MessageLogConsumer first = this;
        final MessageLogConsumer second = other;

        return new MessageLogConsumer() {
            @Override
            public void onRequest(RequestContext ctx, RequestLog req) {
                invokeOnRequest(first, ctx, req);
                second.onRequest(ctx, req);
            }

            @Override
            public void onResponse(RequestContext ctx, ResponseLog res) {
                invokeOnResponse(first, ctx, res);
                second.onResponse(ctx, res);
            }
        };
    }
}

