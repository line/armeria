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

import java.util.EventListener;
import java.util.Objects;

/**
 * Invoked when {@link RequestLog} meets the {@link RequestLogAvailability} specified with
 * {@link RequestLog#addListener(RequestLogListener, RequestLogAvailability)}.
 */
@FunctionalInterface
public interface RequestLogListener extends EventListener {

    /**
     * Invoked when {@link RequestLog} meets the {@link RequestLogAvailability} specified with
     * {@link RequestLog#addListener(RequestLogListener, RequestLogAvailability)}.
     */
    void onRequestLog(RequestLog log) throws Exception;

    /**
     * Returns a composed listener that calls this listener first and then the specified one.
     */
    default RequestLogListener andThen(RequestLogListener other) {
        Objects.requireNonNull(other, "other");

        final RequestLogListener first = this;
        final RequestLogListener second = other;

        return log -> {
            RequestLogListenerInvoker.invokeOnRequestLog(first, log);
            second.onRequestLog(log);
        };
    }
}

