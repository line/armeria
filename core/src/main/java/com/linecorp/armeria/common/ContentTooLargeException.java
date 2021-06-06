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
 * A {@link RuntimeException} raised when the length of request or response content exceeds its limit.
 */
public final class ContentTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 4901614315474105954L;

    private static final ContentTooLargeException INSTANCE = new ContentTooLargeException(false);

    private long transferred;
    private long total;
    private long maximum;

    /**
     * Returns a {@link ContentTooLargeExceptionBuilder} which may return a singleton or a new instance in its
     * {@code build} method, depending on {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static ContentTooLargeExceptionBuilder builder() {
        return new ContentTooLargeExceptionBuilder();
    }

    ContentTooLargeException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }

    ContentTooLargeException(long transferred, long total, long maximum) {
        super(String.format("content length too large: %d + %d > %d", transferred, total, maximum));

        this.transferred = transferred;
        this.total = total;
        this.maximum = maximum;
    }

    /**
     * Returns how many bytes of the content have been transferred.
     */
    public long transferred() {
        return transferred;
    }

    /**
     * Returns the expected total number of bytes in the request or response.
     */
    public long total() {
        return total;
    }

    /**
     * Returns the maximum number of content bytes allowed.
     */
    public long maximum() {
        return maximum;
    }
}
