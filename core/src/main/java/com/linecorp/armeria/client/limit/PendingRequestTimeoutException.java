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

package com.linecorp.armeria.client.limit;

import com.linecorp.armeria.common.TimeoutException;

/**
 * A {@link TimeoutException} raised only when the client was not able to send a request until it reaches at the timeout.
 */
public final class PendingRequestTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 2380973537286999696L;

    private static final PendingRequestTimeoutException INSTANCE = new PendingRequestTimeoutException();

    /**
     * Returns a singleton {@link PendingRequestTimeoutException}.
     */
    public static PendingRequestTimeoutException get() {
        return INSTANCE;
    }

    private PendingRequestTimeoutException() {
        super(null, null, false, false);
    }
}
