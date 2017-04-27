/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.math.LongMath.saturatedAdd;
import static com.linecorp.armeria.client.retry.AbstractBackoff.validateNumAttemptsSoFar;
import static java.util.Objects.requireNonNull;

import java.util.Random;
import java.util.function.Supplier;

import com.google.common.base.MoreObjects;

final class JitterAddingBackoff extends BackoffWrapper {
    private final Supplier<Random> randomSupplier;
    private final long bound;
    private final long minJitterMillis;
    private final long maxJitterMillis;

    JitterAddingBackoff(Backoff delegate, long minJitterMillis, long maxJitterMillis,
                        Supplier<Random> randomSupplier) {
        super(delegate);

        this.randomSupplier = requireNonNull(randomSupplier, "randomSupplier");
        this.minJitterMillis = Math.min(minJitterMillis, maxJitterMillis);
        this.maxJitterMillis = Math.max(minJitterMillis, maxJitterMillis);

        if (minJitterMillis < 0) {
            checkArgument(maxJitterMillis < Long.MAX_VALUE + minJitterMillis,
                          "maxJitterMillis - minJitterMillis must be less than Long.MAX_VALUE.");
        }
        bound = maxJitterMillis - minJitterMillis + 1;
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        validateNumAttemptsSoFar(numAttemptsSoFar);
        final long nextIntervalMillis = delegate().nextIntervalMillis(numAttemptsSoFar);
        if (nextIntervalMillis < 0) {
            return nextIntervalMillis;
        }

        final long jitterMillis = RandomBackoff.nextLong(randomSupplier.get(), bound) + minJitterMillis;
        return Math.max(0, saturatedAdd(nextIntervalMillis, jitterMillis));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate())
                          .add("minJitterMillis", minJitterMillis)
                          .add("maxJitterMillis", maxJitterMillis)
                          .toString();
    }
}
