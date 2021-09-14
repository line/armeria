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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

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
    private final long maxContentLength;
    private final long contentLength;
    private final long transferred;

    private ContentTooLargeException() {
        this(false);
    }

    private ContentTooLargeException(boolean neverSample) {
        super(null, null, !neverSample, !neverSample);

        this.neverSample = neverSample;
        maxContentLength = -1;
        transferred = -1;
        contentLength = -1;
    }

    ContentTooLargeException(long maxContentLength, long contentLength, long transferred) {
        super(toString(maxContentLength, contentLength, transferred));

        neverSample = false;
        this.transferred = transferred;
        this.contentLength = contentLength;
        this.maxContentLength = maxContentLength;
    }

    /**
     * Returns the number of bytes transferred so far, or {@code -1} if this value is not known.
     */
    public long transferred() {
        return transferred;
    }

    /**
     * Returns the actual content length in bytes, as specified in the {@code content-length} header,
     * or {@code -1} if this value is not known.
     */
    public long contentLength() {
        return contentLength;
    }

    /**
     * Returns the maximum allowed content length in bytes, or {@code -1} if this value is not known.
     */
    public long maxContentLength() {
        return maxContentLength;
    }

    @Override
    public Throwable fillInStackTrace() {
        if (!neverSample && Flags.verboseExceptionSampler().isSampled(getClass())) {
            super.fillInStackTrace();
        }
        return this;
    }

    @Nullable
    private static String toString(long maxContentLength, long contentLength, long transferred) {
        try (TemporaryThreadLocals ttl = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = ttl.stringBuilder();
            if (maxContentLength >= 0) {
                buf.append(", maxContentLength: ").append(maxContentLength);
            }
            if (contentLength >= 0) {
                buf.append(", contentLength: ").append(contentLength);
            }
            if (transferred >= 0) {
                buf.append(", transferred: ").append(transferred);
            }
            return buf.length() != 0 ? buf.substring(2) : null;
        }
    }
}
