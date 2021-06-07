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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Builds a new {@link ContentTooLargeException}.
 */
public final class ContentTooLargeExceptionBuilder {
    private long transferred = -1;
    private long delta = -1;
    private long limit = -1;

    ContentTooLargeExceptionBuilder() {
    }

    /**
     * Sets the number of bytes transferred so far.
     */
    public ContentTooLargeExceptionBuilder transferred(long transferred) {
        checkArgument(transferred >= 0, "transferred: %s (expected: >= 0)", transferred);
        this.transferred = transferred;
        return this;
    }

    /**
     * Sets the number of bytes that were being transferred additionally.
     */
    public ContentTooLargeExceptionBuilder delta(long delta) {
        checkArgument(delta >= 0, "delta: %s (expected: >= 0)", delta);
        this.delta = delta;
        return this;
    }

    /**
     * Sets the maximum allowed content length in bytes.
     */
    public ContentTooLargeExceptionBuilder limit(long limit) {
        checkArgument(limit >= 0, "limit: %s (expected: >= 0)", limit);
        this.limit = limit;
        return this;
    }

    /**
     * Returns a new instance of {@link ContentTooLargeException}.
     */
    public ContentTooLargeException build() {
        if (transferred < 0 && delta < 0 && limit < 0) {
            return ContentTooLargeException.get();
        }
        return new ContentTooLargeException(transferred, delta, limit);
    }
}
