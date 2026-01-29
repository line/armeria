/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A handle that allows an ongoing operation (e.g. a subscription, watch, or scheduled task)
 * to be canceled.
 *
 * <p>Calling {@link #cancel()} should stop further callbacks and release any associated resources
 * where possible. Implementations should be idempotent, meaning calling {@link #cancel()} multiple
 * times has no additional effect.
 */
@UnstableApi
@FunctionalInterface
public interface Cancelable {

    /**
     * Cancels the associated operation.
     *
     * <p>If the operation is already cancelled or has already completed, this method should do
     * nothing.
     */
    void cancel();
}
