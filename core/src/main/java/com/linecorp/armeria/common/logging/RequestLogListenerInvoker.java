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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Utility methods that invokes the callback methods of {@link RequestLogListener} safely.
 *
 * <p>They catch the exceptions raised by the callback methods and log them at WARN level.
 */
public final class RequestLogListenerInvoker {

    private static final Logger logger = LoggerFactory.getLogger(RequestLogListenerInvoker.class);

    /**
     * Invokes {@link RequestLogListener#onRequestLog(RequestLog)}.
     */
    public static void invokeOnRequestLog(RequestLogListener listener, RequestLog log) {
        requireNonNull(listener, "listener");
        requireNonNull(log, "log");
        try (SafeCloseable ignored = log.context().push()) {
            listener.onRequestLog(log);
        } catch (Throwable e) {
            logger.warn("onRequestLog() failed with an exception:", e);
        }
    }

    /**
     * Invokes {@link RequestLogListener#onRequestLog(RequestLog)}.
     */
    static void invokeOnRequestLog(RequestLogListener[] listeners, RequestLog log) {
        requireNonNull(listeners, "listeners");
        requireNonNull(log, "log");
        if (listeners.length == 0) {
            return;
        }

        try (SafeCloseable ignored = log.context().push()) {
            for (RequestLogListener listener : listeners) {
                if (listener == null) {
                    break;
                }
                try {
                    listener.onRequestLog(log);
                } catch (Throwable e) {
                    logger.warn("onRequestLog() failed with an exception:", e);
                }
            }
        }
    }

    private RequestLogListenerInvoker() {}
}
