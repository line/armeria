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

import java.util.concurrent.CompletableFuture;

/**
 * A concurrency limiter based on an AsyncSemaphore.
 */
public interface ConcurrencyLimit<Context> {
    /**
     * Acquire a {@link Permit}, asynchronously.
     *
     * <p>Make sure to `permit.release()` once the operation guarded by the permit completes successfully or
     * with error.
     *
     * @return the {@link Permit}
     */
    CompletableFuture<Permit> acquire(Context ctx);

    /**
     * Token representing an interest in a resource and a way to release that interest.
     */
    interface Permit {
        /**
         * Indicate that you are done with your Permit.
         */
        void release();
    }
}
