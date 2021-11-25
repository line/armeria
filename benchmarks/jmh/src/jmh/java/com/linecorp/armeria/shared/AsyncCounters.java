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

package com.linecorp.armeria.shared;

import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Counters useful for measuring asynchronous requests. It is important for users to call
 * {@link #incrementCurrentRequests()} and {@link #decrementCurrentRequests()} at beginning and end of a
 * request to make sure all requests are completed before moving onto the next benchmark iteration.
 */
@AuxCounters
@State(Scope.Thread)
public class AsyncCounters {
    private final AtomicLong numSuccesses = new AtomicLong();
    private final AtomicLong numFailures = new AtomicLong();
    private final AtomicLong currentRequests = new AtomicLong();

    private volatile boolean waiting;

    public void incrementNumSuccesses() {
        if (!waiting) {
            numSuccesses.incrementAndGet();
        }
    }

    public void incrementNumFailures() {
        if (!waiting) {
            numFailures.incrementAndGet();
        }
    }

    public void incrementCurrentRequests() {
        currentRequests.incrementAndGet();
    }

    public void decrementCurrentRequests() {
        currentRequests.decrementAndGet();
    }

    public long numSuccesses() {
        return numSuccesses.get();
    }

    public long numFailures() {
        return numFailures.get();
    }

    public long currentRequests() {
        return currentRequests.get();
    }

    @Setup(Level.Iteration)
    public void reset() {
        waiting = false;
        numSuccesses.set(0);
        numFailures.set(0);
        currentRequests.set(0);
    }

    @TearDown(Level.Iteration)
    public void waitForCurrentRequests() {
        waiting = true;
        await().forever().until(() -> currentRequests.get() == 0);
    }
}
