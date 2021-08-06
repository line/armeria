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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Produces the preview of {@link RequestLog}.
 */
public interface ContentPreviewer {

    /**
     * Returns a dummy {@link ContentPreviewer} which produces {@code null}.
     */
    static ContentPreviewer disabled() {
        return NoopContentPreviewer.NOOP;
    }

    /**
     * Returns whether this {@link ContentPreviewer} is {@link #disabled()} or not.
     */
    default boolean isDisabled() {
        return false;
    }

    /**
     * Invoked after request/response data is received.
     */
    void onData(HttpData data);

    /**
     * Produces the preview of the request or response.
     *
     * @return the preview, or {@code null} if this previewer is disabled or does not produce anything
     */
    @Nullable
    String produce();
}
