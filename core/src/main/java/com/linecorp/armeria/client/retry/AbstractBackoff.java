/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A skeletal {@link Backoff} implementation.
 */
public abstract class AbstractBackoff implements Backoff {

    static void validateNumAttemptsSoFar(int numAttemptsSoFar) {
        checkArgument(numAttemptsSoFar > 0, "numAttemptsSoFar: %s (expected: > 0)", numAttemptsSoFar);
    }

    @Override
    public final long nextDelayMillis(int numAttemptsSoFar) {
        validateNumAttemptsSoFar(numAttemptsSoFar);
        return doNextDelayMillis(numAttemptsSoFar);
    }

    /**
     * Invoked by {@link #nextDelayMillis(int)} after {@code numAttemptsSoFar} is validated.
     */
    protected abstract long doNextDelayMillis(int numAttemptsSoFar);
}
