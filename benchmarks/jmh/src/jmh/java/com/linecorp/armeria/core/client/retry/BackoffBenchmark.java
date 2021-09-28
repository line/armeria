/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.core.client.retry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.client.retry.Backoff;

/**
 * Microbenchmarks of an {@link Backoff#exponential(long, long, double) expontational backoff}.
 */
@State(Scope.Benchmark)
public class BackoffBenchmark {

    private static final Backoff EXPONENTIAL_BACKOFF = Backoff.exponential(100, 5000, 2.0);

    @Param({ "1", "2", "3", "4", "5", "10", "20" })
    private int numAttemptsSoFar;

    @Benchmark
    public long exponential() {
        return EXPONENTIAL_BACKOFF.nextDelayMillis(numAttemptsSoFar);
    }
}
