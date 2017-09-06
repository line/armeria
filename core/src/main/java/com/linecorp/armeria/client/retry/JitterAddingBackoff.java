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
    private final double minJitterRate;
    private final double maxJitterRate;

    JitterAddingBackoff(Backoff delegate, double minJitterRate, double maxJitterRate,
                        Supplier<Random> randomSupplier) {
        super(delegate);

        this.randomSupplier = requireNonNull(randomSupplier, "randomSupplier");
        checkArgument(-1.0 <= minJitterRate && minJitterRate <= 1.0,
                      "minJitterRate: %s (expected: >= -1.0 and <= 1.0)", minJitterRate);
        checkArgument(-1.0 <= maxJitterRate && maxJitterRate <= 1.0,
                      "maxJitterRate: %s (expected: >= -1.0 and <= 1.0)", maxJitterRate);
        checkArgument(minJitterRate <= maxJitterRate,
                      "maxJitterRate: %s needs to be greater than or equal to minJitterRate: %s",
                      maxJitterRate, minJitterRate);

        this.minJitterRate = minJitterRate;
        this.maxJitterRate = maxJitterRate;
    }

    @Override
    public long nextDelayMillis(int numAttemptsSoFar) {
        validateNumAttemptsSoFar(numAttemptsSoFar);
        final long nextDelayMillis = delegate().nextDelayMillis(numAttemptsSoFar);
        if (nextDelayMillis < 0) {
            return nextDelayMillis;
        }

        final long minJitter = (long) (nextDelayMillis * (1 + minJitterRate));
        final long maxJitter = (long) (nextDelayMillis * (1 + maxJitterRate));
        final long bound = maxJitter - minJitter + 1;
        final long millis = RandomBackoff.nextLong(randomSupplier.get(), bound);
        return Math.max(0, saturatedAdd(minJitter, millis));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate())
                          .add("minJitterRate", minJitterRate)
                          .add("maxJitterRate", maxJitterRate)
                          .toString();
    }
}
