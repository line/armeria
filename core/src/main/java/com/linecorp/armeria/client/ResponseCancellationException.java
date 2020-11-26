/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.CancellationException;
import com.linecorp.armeria.common.Flags;

/**
 * A {@link CancellationException} raised when a response is cancelled by the user.
 */
public final class ResponseCancellationException extends CancellationException {

    private static final long serialVersionUID = 758179602424047751L;

    private static final ResponseCancellationException INSTANCE = new ResponseCancellationException(false);

    /**
     * Returns a singleton {@link ResponseCancellationException}.
     */
    public static ResponseCancellationException get() {
        return Flags.verboseExceptionSampler().isSampled(ResponseCancellationException.class) ?
               new ResponseCancellationException() : INSTANCE;
    }

    private ResponseCancellationException() {}

    private ResponseCancellationException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
