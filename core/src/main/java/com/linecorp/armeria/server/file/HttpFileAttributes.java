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
package com.linecorp.armeria.server.file;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Date;
import java.util.concurrent.Executor;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.DateFormatter;

/**
 * The attributes of an {@link HttpFile}.
 *
 * @see HttpFile#readAttributes(Executor)
 */
public final class HttpFileAttributes {

    private final long length;
    private final long lastModifiedMillis;

    /**
     * Creates a new instance.
     *
     * @param length the length in bytes
     * @param lastModifiedMillis the last modified time represented as the number of milliseconds
     *                           since the epoch
     */
    public HttpFileAttributes(long length, long lastModifiedMillis) {
        checkArgument(length >= 0, "length: %s (expected: >= 0)", length);
        this.length = length;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    /**
     * Returns the length in bytes.
     */
    public long length() {
        return length;
    }

    /**
     * Returns the last modified time represented as the number of milliseconds since the epoch.
     */
    public long lastModifiedMillis() {
        return lastModifiedMillis;
    }

    @Override
    public int hashCode() {
        return (int) (length * 31 + lastModifiedMillis);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null || obj.getClass() != HttpFileAttributes.class) {
            return false;
        }

        final HttpFileAttributes that = (HttpFileAttributes) obj;
        return length == that.length && lastModifiedMillis == that.lastModifiedMillis;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("length", length)
                          .add("lastModified", DateFormatter.format(new Date(lastModifiedMillis)))
                          .toString();
    }
}
