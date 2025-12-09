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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A listener that listens to a specific event of a {@link RequestLog}.
 *
 * <p>If a {@link RequestLogProperty} was completed before adding this listener to the {@link RequestLog},
 * the {@link #onEvent(RequestLogProperty, RequestLog)} method will be invoked immediately with the already
 * completed property upon adding the listener.
 *
 * <p>Note that this listener may be invoked in the I/O worker thread so make sure to offload any blocking
 * operations to a separate thread pool.
 */
@UnstableApi
@FunctionalInterface
public interface RequestLogListener {

    /**
     * Invoked when the specified {@link RequestLogProperty} is completed.
     */
    void onEvent(RequestLogProperty property, RequestLog log);
}
