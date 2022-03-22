/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.logging.AccessLogFormats.parseCustom;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.Service;

/**
 * Consumes the {@link RequestLog}s produced by a {@link Service}, usually for logging purpose.
 */
@FunctionalInterface
public interface AccessLogWriter {

    /**
     * Returns an access log writer with a common format.
     */
    static AccessLogWriter common() {
        return requestLog -> AccessLogger.write(AccessLogFormats.COMMON, requestLog);
    }

    /**
     * Returns an access log writer with a combined format.
     */
    static AccessLogWriter combined() {
        return requestLog -> AccessLogger.write(AccessLogFormats.COMBINED, requestLog);
    }

    /**
     * Returns disabled access log writer.
     */
    static AccessLogWriter disabled() {
        return requestLog -> { /* No operation. */ };
    }

    /**
     * Returns an access log writer with the specified {@code formatStr}.
     */
    static AccessLogWriter custom(String formatStr) {
        requireNonNull(formatStr, "formatStr");
        final List<AccessLogComponent> accessLogFormat = parseCustom(formatStr);
        checkArgument(!accessLogFormat.isEmpty(), "Invalid access log format string: %s", formatStr);
        return requestLog -> AccessLogger.write(accessLogFormat, requestLog);
    }

    /**
     * Logs the specified {@link RequestLog}.
     */
    void log(RequestLog log);

    /**
     * Returns a new {@link AccessLogWriter} which combines two {@link AccessLogWriter}s.
     */
    default AccessLogWriter andThen(AccessLogWriter after) {
        return new AccessLogWriter() {
            @Override
            public void log(RequestLog log) {
                try {
                    AccessLogWriter.this.log(log);
                } finally {
                    after.log(log);
                }
            }

            @Override
            public CompletableFuture<Void> shutdown() {
                final CompletableFuture<Void> f1;
                final CompletableFuture<Void> f2;
                try {
                    f1 = AccessLogWriter.this.shutdown();
                } finally {
                    f2 = after.shutdown();
                }
                return CompletableFuture.allOf(f1, f2);
            }
        };
    }

    /**
     * Shuts down this {@link AccessLogWriter}.
     *
     * @return the {@link CompletableFuture} which is completed
     *         when this {@link AccessLogWriter} has been shut down.
     */
    default CompletableFuture<Void> shutdown() {
        return UnmodifiableFuture.completedFuture(null);
    }
}
