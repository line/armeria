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

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} that is raised when a {@link RetryingClient} gives up retrying due to the
 * result of {@link Backoff#nextDelayMillis(int)}.
 */
public final class RetryGiveUpException extends RuntimeException {

    private static final long serialVersionUID = -3816065469543230534L;

    private static final RetryGiveUpException INSTANCE = Exceptions.clearTrace(new RetryGiveUpException());

    /**
     * Returns a {@link RetryGiveUpException} which may be a singleton or a new instance, depending
     * on whether {@link Flags#verboseExceptions() the verbose exception mode} is enabled.
     */
    public static RetryGiveUpException get() {
        return Flags.verboseExceptions() ? new RetryGiveUpException() : INSTANCE;
    }

    private RetryGiveUpException() {}
}
