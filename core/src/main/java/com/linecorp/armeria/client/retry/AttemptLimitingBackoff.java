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
import static com.linecorp.armeria.client.retry.AbstractBackoff.validateNumAttemptsSoFar;

import com.google.common.base.MoreObjects;

final class AttemptLimitingBackoff extends BackoffWrapper {
    private final int maxAttempts;

    AttemptLimitingBackoff(Backoff backoff, int maxAttempts) {
        super(backoff);
        checkArgument(maxAttempts > 0, "maxAttempts: %s (expected: > 0)", maxAttempts);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long nextDelayMillis(int numAttemptsSoFar) {
        validateNumAttemptsSoFar(numAttemptsSoFar);
        if (numAttemptsSoFar >= maxAttempts) {
            return -1;
        }
        return super.nextDelayMillis(numAttemptsSoFar);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate())
                          .add("maxAttempts", maxAttempts)
                          .toString();
    }
}
