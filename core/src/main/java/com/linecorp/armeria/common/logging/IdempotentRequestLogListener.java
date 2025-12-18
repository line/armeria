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

import static com.linecorp.armeria.common.logging.DefaultRequestLog.hasInterestedFlags;

import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * A {@link RequestLogListener} that delivers each event only once to the delegate listener.
 */
final class IdempotentRequestLogListener extends ReentrantShortLock implements RequestLogListener {

    private static final long serialVersionUID = -573237359665852226L;

    private final RequestLogListener delegate;
    private int notifiedFlags;

    IdempotentRequestLogListener(RequestLogListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvent(RequestLogProperty property, RequestLog log) {
        lock();
        try {
            if (hasInterestedFlags(notifiedFlags, property)) {
                // Already notified.
                return;
            }
            notifiedFlags |= property.flag();
        } finally {
            unlock();
        }
        delegate.onEvent(property, log);
    }
}
