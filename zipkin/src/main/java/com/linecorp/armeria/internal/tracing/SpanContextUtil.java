/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.tracing;

import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.logging.RequestLog;

import brave.Span;

public final class SpanContextUtil {

    /**
     * Starts the {@link Span} when the log is ready.
     */
    public static void startSpan(Span span, RequestLog log) {
        span.start(log.requestStartTimeMicros());
    }

    /**
     * Adds logging tags to the provided {@link Span} and closes it when the log is finished.
     * The span cannot be used further after this method has been called.
     */
    public static void closeSpan(Span span, RequestLog log) {
        SpanTags.addTags(span, log);
        span.finish(wallTimeMicros(log, log.responseEndTimeNanos()));
    }

    static long wallTimeMicros(RequestLog log, long timeNanos) {
        long relativeTimeNanos = timeNanos - log.requestStartTimeNanos();
        return log.requestStartTimeMicros() + TimeUnit.NANOSECONDS.toMicros(relativeTimeNanos);
    }

    private SpanContextUtil() {}
}
