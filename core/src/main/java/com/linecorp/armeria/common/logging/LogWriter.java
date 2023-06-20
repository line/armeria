/*
 * Copyright 2023 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Writes logs of a {@link RequestLog}.
 */
@UnstableApi
public interface LogWriter {

    /**
     * Returns the default {@link LogWriter}.
     */
    static LogWriter of() {
        return DefaultLogWriter.DEFAULT;
    }

    /**
     * Returns a newly created {@link LogWriter} with the specified {@link Logger}.
     */
    static LogWriter of(Logger logger) {
        return builder().logger(logger).build();
    }

    /**
     * Returns a newly created {@link LogWriter} with the specified {@link LogFormatter}.
     */
    static LogWriter of(LogFormatter logFormatter) {
        return builder().logFormatter(logFormatter).build();
    }

    /**
     * Returns the {@link LogWriterBuilder} for building a new {@link LogWriter}.
     */
    static LogWriterBuilder builder() {
        return new LogWriterBuilder();
    }

    /**
     * Writes the request-side {@link RequestOnlyLog}.
     */
    void logRequest(RequestOnlyLog log);

    /**
     * Writes the response-side {@link RequestLog}.
     */
    void logResponse(RequestLog log);

    /**
     * Returns a new {@link LogWriter} which combines two {@link LogWriter}s.
     */
    default LogWriter andThen(LogWriter after) {
        requireNonNull(after, "after");
        return new LogWriter() {
            @Override
            public void logRequest(RequestOnlyLog log) {
                try {
                    LogWriter.this.logRequest(log);
                } finally {
                    after.logRequest(log);
                }
            }

            @Override
            public void logResponse(RequestLog log) {
                try {
                    LogWriter.this.logResponse(log);
                } finally {
                    after.logResponse(log);
                }
            }
        };
    }
}
