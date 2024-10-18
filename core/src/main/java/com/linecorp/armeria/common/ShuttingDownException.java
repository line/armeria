/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.Server;

/**
 * A {@link CancellationException} raised when a {@link Server} cannot handle a request because it's shutting
 * down.
 */
@UnstableApi
public final class ShuttingDownException extends CancellationException {
    private static final long serialVersionUID = -4963725400532294491L;

    private static final ShuttingDownException INSTANCE = new ShuttingDownException(false);

    /**
     * Returns a singleton {@link ShuttingDownException} or newly-created exception depending on
     * the result of {@link Sampler#isSampled(Object)} of {@link Flags#verboseExceptionSampler()}.
     */
    public static ShuttingDownException get() {
        return Flags.verboseExceptionSampler().isSampled(ShuttingDownException.class) ?
               new ShuttingDownException() : INSTANCE;
    }

    private ShuttingDownException() {}

    private ShuttingDownException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
