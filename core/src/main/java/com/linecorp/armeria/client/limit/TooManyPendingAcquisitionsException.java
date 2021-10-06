/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client.limit;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Sampler;

/**
 * A {@link RuntimeException} raised when the number of pending acquisitions exceeds the
 * {@link ConcurrencyLimitBuilder#maxPendingAcquisitions(int)}.
 */
public final class TooManyPendingAcquisitionsException extends RuntimeException {

    private static final long serialVersionUID = 4362262171142323323L;

    private static final TooManyPendingAcquisitionsException INSTANCE =
            new TooManyPendingAcquisitionsException(false);

    /**
     * Returns a singleton {@link TooManyPendingAcquisitionsException} or newly-created exception
     * depending on the result of {@link Sampler#isSampled(Object)} of {@link Flags#verboseExceptionSampler()}.
     */
    public static TooManyPendingAcquisitionsException get() {
        return Flags.verboseExceptionSampler().isSampled(TooManyPendingAcquisitionsException.class) ?
               new TooManyPendingAcquisitionsException() : INSTANCE;
    }

    private TooManyPendingAcquisitionsException() {}

    private TooManyPendingAcquisitionsException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
