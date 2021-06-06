/*
 * Copyright 2016 LINE Corporation
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

/**
 * Builds a new {@link ContentTooLargeException} or a singleton, depending on
 * {@link Flags#verboseExceptionSampler()}'s decision.
 */
public final class ContentTooLargeExceptionBuilder {
    private static final ContentTooLargeException INSTANCE = new ContentTooLargeException(false);

    private long transferred;
    private long total;
    private long maximum;

    ContentTooLargeExceptionBuilder() {
    }

    /**
     * Sets the transferred bytes of the content.
     */
    public ContentTooLargeExceptionBuilder transferred(long transferred) {
        this.transferred = transferred;
        return this;
    }

    /**
     * Sets the total bytes of the content.
     */
    public ContentTooLargeExceptionBuilder total(long total) {
        this.total = total;
        return this;
    }

    /**
     * Sets the maximum allowed bytes of the content.
     */
    public ContentTooLargeExceptionBuilder maximum(long maximum) {
        this.maximum = maximum;
        return this;
    }

    /**
     * Returns a singleton or a new instance of {@link ContentTooLargeException},
     * depending on {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public ContentTooLargeException build() {
        return Flags.verboseExceptionSampler().isSampled(ContentTooLargeException.class) ?
               new ContentTooLargeException(transferred, total, maximum) : INSTANCE;
    }
}
