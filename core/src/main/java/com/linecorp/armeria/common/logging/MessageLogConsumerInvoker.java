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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;

/**
 * Utility methods that invokes the callback methods of {@link MessageLogConsumer} safely.
 *
 * <p>They catch the exceptions raised by the callback methods and log them at WARN level.
 */
public final class MessageLogConsumerInvoker {

    private static final Logger logger = LoggerFactory.getLogger(MessageLogConsumerInvoker.class);

    /**
     * Invokes {@link MessageLogConsumer#onRequest(RequestContext, RequestLog)}.
     */
    public static void invokeOnRequest(MessageLogConsumer consumer, RequestContext ctx, RequestLog req) {
        try {
            consumer.onRequest(ctx, req);
        } catch (Throwable e) {
            logger.warn("onRequest() failed with an exception: {}", e);
        }
    }

    /**
     * Invokes {@link MessageLogConsumer#onResponse(RequestContext, ResponseLog)}.
     */
    public static void invokeOnResponse(MessageLogConsumer consumer, RequestContext ctx, ResponseLog res) {
        try {
            consumer.onResponse(ctx, res);
        } catch (Throwable e) {
            logger.warn("onResponse() failed with an exception: {}", e);
        }
    }

    private MessageLogConsumerInvoker() {}
}
