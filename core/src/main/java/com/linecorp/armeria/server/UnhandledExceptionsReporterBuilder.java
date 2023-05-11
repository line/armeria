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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.BiPredicate;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A builder for creating the default built-in {@link UnhandledExceptionsReporter}.
 */
public class UnhandledExceptionsReporterBuilder {

    private final long unhandledExceptionsReportIntervalMillis;
    private MeterRegistry meterRegistry = Flags.meterRegistry();
    @Nullable
    private BiPredicate<ServiceRequestContext, Throwable> ignorePredicate;

    UnhandledExceptionsReporterBuilder(long unhandledExceptionsReportIntervalMillis) {
        checkArgument(unhandledExceptionsReportIntervalMillis > 0);
        this.unhandledExceptionsReportIntervalMillis = unhandledExceptionsReportIntervalMillis;
    }

    /**
     * Sets the {@link MeterRegistry} which will be used to record metrics on unhandled exceptions.
     */
    public UnhandledExceptionsReporterBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Sets the {@link BiPredicate} which will be used to determine which exceptions to ignore.
     */
    public UnhandledExceptionsReporterBuilder ignorePredicate(
            BiPredicate<ServiceRequestContext, Throwable> ignorePredicate) {
        this.ignorePredicate = requireNonNull(ignorePredicate, "ignorePredicate");
        return this;
    }

    /**
     * Builds the {@link UnhandledExceptionsReporter} with the provided parameters.
     */
    public UnhandledExceptionsReporter build() {
        if (ignorePredicate == null) {
            return new DefaultUnhandledExceptionsReporter(meterRegistry,
                                                          unhandledExceptionsReportIntervalMillis);
        }
        return new DefaultUnhandledExceptionsReporter(meterRegistry, unhandledExceptionsReportIntervalMillis,
                                                      ignorePredicate);
    }
}
