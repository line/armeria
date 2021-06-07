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

    private static final ContentTooLargeException INSTANCE = new ContentTooLargeException(true);

    /**
     * Returns a {@link ContentTooLargeException} which may be a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static ContentTooLargeException get() {
        return Flags.verboseExceptionSampler().isSampled(ContentTooLargeException.class) ?
               new ContentTooLargeException() : INSTANCE;
    }

    /**
     * Returns a {@link ContentTooLargeExceptionBuilder} which may return a singleton or a new instance in its
     * {@link ContentTooLargeExceptionBuilder#build()} method, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static ContentTooLargeExceptionBuilder builder() {
        return new ContentTooLargeExceptionBuilder();
    }

    private final boolean neverSample;
    private final long transferred;
    private final long delta;
    private final long limit;

    private ContentTooLargeException() {
        this(false);
    }

    private ContentTooLargeException(boolean neverSample) {
        super(null, null, !neverSample, !neverSample);
        this.neverSample = neverSample;
        limit = -1;
        transferred = -1;
        delta = -1;
    }

    ContentTooLargeException(long transferred, long delta, long limit) {
        super(String.format("content length too large: transferred(%d) + delta(%d) > limit(%d)",
                            transferred, delta, limit));
        neverSample = false;
        this.transferred = transferred;
        this.delta = delta;
        this.limit = limit;
    }

    /**
     * Returns the number of bytes transferred so far,
     * or {@code -1} if this value is not known.
     */
    public long transferred() {
        return transferred;
    }

    /**
     * Returns the number of bytes that were being transferred additionally,
     * or {@code -1} if this value is not known.
     */
    public long delta() {
        return delta;
    }

    /**
     * Returns the maximum allowed content length in bytes,
     * or {@code -1} if this value is not known.
     */
    public long limit() {
        return limit;
    }

    @Override
    public Throwable fillInStackTrace() {
        if (!neverSample && Flags.verboseExceptionSampler().isSampled(getClass())) {
            super.fillInStackTrace();
        }
        return this;
    }
}
