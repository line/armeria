/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.common.Flags;

/**
 * A {@link RuntimeException} that is raised by a {@link RetriedRequest} to signal to the caller of
 * {@link RetriedRequest#executeAttempt} that the attempt has been aborted because the request has been
 * completed - either at call time or during the execution of the attempt.
 */
public final class AbortedAttemptException extends RuntimeException {
    private static final long serialVersionUID = -1L;
    private static final AbortedAttemptException INSTANCE = new AbortedAttemptException(true);

    /**
     * Returns a {@link AbortedAttemptException} which may be a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static AbortedAttemptException get() {
        return Flags.verboseExceptionSampler().isSampled(
                AbortedAttemptException.class) ?
               new AbortedAttemptException() : INSTANCE;
    }

    private AbortedAttemptException() {}

    private AbortedAttemptException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
