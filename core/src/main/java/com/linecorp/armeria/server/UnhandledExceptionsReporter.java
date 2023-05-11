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

package com.linecorp.armeria.server;

import com.linecorp.armeria.server.logging.LoggingService;

/**
 * A reporter which reports unhandled exceptions for each request.
 * An unhandled exception is an exception which satisfies the following conditions:
 * <ul>
 *     <li>An exception for a request which is not logged by a {@link LoggingService}</li>
 *     <li>An exception for a request which is not explicitly disabled using
 *     {@link ServiceRequestContext#setShouldReportUnhandledExceptions(boolean)}</li>
 * </ul>
 * This reporter may be set via {@link ServerBuilder#unhandledExceptionsReporter(UnhandledExceptionsReporter)}.
 * <pre>{@code
 * Server.builder()
 * ...
 *       .unhandledExceptionsReporter(new UnhandledExceptionsReporter() {
 *           @Override
 *           public void report(Throwable cause) {
 *               logger.info("Unhandled exception: ", cause);
 *           }
 *       }).build().start();
 * }</pre>
 */
public interface UnhandledExceptionsReporter {

    /**
     * A builder for creating the default built-in {@link UnhandledExceptionsReporter}.
     */
    static UnhandledExceptionsReporterBuilder builder(long unhandledExceptionsReportIntervalMillis) {
        return new UnhandledExceptionsReporterBuilder(unhandledExceptionsReportIntervalMillis);
    }

    /**
     * A callback method which is invoked whenever an unhandled exception is reported.
     */
    void report(ServiceRequestContext ctx, Throwable cause);

    /**
     * The interval by which {@link UnhandledExceptionsReporter} may report
     * unhandled exceptions. Note that this is an optional value which implementations
     * may choose not to override.
     */
    default long intervalMillis() {
        return 0;
    }
}
