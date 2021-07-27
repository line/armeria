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
import static java.util.Objects.requireNonNull;

/**
 * Builds a new {@link ContentTooLargeException}.
 */
public final class ContentTooLargeExceptionBuilder {

    private long maxContentLength = -1;
    private long contentLength = -1;
    private long transferred = -1;

    ContentTooLargeExceptionBuilder() {}

    /**
     * Sets the maximum allowed content length in bytes.
     */
    public ContentTooLargeExceptionBuilder maxContentLength(long maxContentLength) {
        checkArgument(maxContentLength >= 0, "maxContentLength: %s (expected: >= 0)", maxContentLength);
        this.maxContentLength = maxContentLength;
        return this;
    }

    /**
     * Sets the actual content length in bytes, as specified in the {@code content-length} header.
     */
    public ContentTooLargeExceptionBuilder contentLength(long contentLength) {
        checkArgument(contentLength >= 0, "contentLength: %s (expected: >= 0)", contentLength);
        this.contentLength = contentLength;
        return this;
    }

    /**
     * Sets the actual content length in bytes, as specified in the {@code content-length} header,
     * from the specified {@link HttpHeaders}. If the {@code content-length} header is missing or
     * its value is not valid, {@code -1} (unknown) will be set instead.
     */
    public ContentTooLargeExceptionBuilder contentLength(HttpHeaders headers) {
        requireNonNull(headers, "headers");
        contentLength = headers.contentLength();
        return this;
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
     * Returns a new instance of {@link ContentTooLargeException}.
     */
    public ContentTooLargeException build() {
        if (maxContentLength < 0 && contentLength < 0 && transferred < 0) {
            return ContentTooLargeException.get();
        }
        return new ContentTooLargeException(maxContentLength, contentLength, transferred);
    }
}
