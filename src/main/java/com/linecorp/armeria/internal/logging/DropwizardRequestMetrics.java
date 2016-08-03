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

package com.linecorp.armeria.internal.logging;

import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.Timer;

/**
 * {@link Metric}s for a single request-response pair.
 */
final class DropwizardRequestMetrics {

    private final String name;
    private final Timer timer;
    private final Meter successes;
    private final Meter failures;
    private final Counter activeRequests;
    private final Meter requestBytes;
    private final Meter responseBytes;

    DropwizardRequestMetrics(String name, Timer timer, Meter successes, Meter failures,
                             Counter activeRequests, Meter requestBytes, Meter responseBytes) {

        this.name = name;
        this.timer = timer;
        this.successes = successes;
        this.failures = failures;
        this.activeRequests = activeRequests;
        this.requestBytes = requestBytes;
        this.responseBytes = responseBytes;
    }

    void updateTime(long durationNanos) {
        timer.update(durationNanos, TimeUnit.NANOSECONDS);
    }

    void markSuccess() {
        successes.mark();
    }

    void markFailure() {
        failures.mark();
    }

    void markStart() {
        activeRequests.inc();
    }

    void markComplete() {
        activeRequests.dec();
    }

    void requestBytes(long requestBytes) {
        this.requestBytes.mark(requestBytes);
    }

    void responseBytes(long responseBytes) {
        this.responseBytes.mark(responseBytes);
    }

    @Override
    public String toString() {
        return "<DropwizardRequestMetrics for: " + name + '\n' +
               "  requests: " + timer.getCount() + '\n' +
               "  successes: " + successes.getCount() + '\n' +
               "  failures: " + failures.getCount() + '\n' +
               "  activeRequests: " + activeRequests.getCount() + '\n' +
               "  requestBytes: " + requestBytes.getCount() + '\n' +
               "  responseBytes: " + responseBytes.getCount() + "\n>";
    }
}
