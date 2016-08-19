/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.file;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

/**
 * {@link HttpFileService} configuration.
 */
public final class HttpFileServiceConfig {

    private final HttpVfs vfs;
    private final Clock clock;
    private final int maxCacheEntries;
    private final int maxCacheEntrySizeBytes;
    private final boolean serveCompressedFiles;

    HttpFileServiceConfig(HttpVfs vfs, Clock clock, int maxCacheEntries, int maxCacheEntrySizeBytes,
                          boolean serveCompressedFiles) {
        this.vfs = requireNonNull(vfs, "vfs");
        this.clock = requireNonNull(clock, "clock");
        this.maxCacheEntries = validateMaxCacheEntries(maxCacheEntries);
        this.maxCacheEntrySizeBytes = validateMaxCacheEntrySizeBytes(maxCacheEntrySizeBytes);
        this.serveCompressedFiles = serveCompressedFiles;
    }

    static int validateMaxCacheEntries(int maxCacheEntries) {
        return validateNonNegativeParameter(maxCacheEntries, "maxCacheEntries");
    }

    static int validateMaxCacheEntrySizeBytes(int maxCacheEntrySizeBytes) {
        return validateNonNegativeParameter(maxCacheEntrySizeBytes, "maxCacheEntrySizeBytes");
    }

    private static int validateNonNegativeParameter(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + ": " + value + " (expected: >= 0)");
        }
        return value;
    }

    /**
     * Returns the {@link HttpVfs} that provides the static files to an {@link HttpFileService}.
     */
    public HttpVfs vfs() {
        return vfs;
    }

    /**
     * Returns the {@link Clock} the provides the current date and time to an {@link HttpFileService}.
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Returns the maximum allowed number of cached file entries.
     */
    public int maxCacheEntries() {
        return maxCacheEntries;
    }

    /**
     * Returns the maximum allowed size of a cached file entry. Files bigger than this value will not be
     * cached.
     */
    public int maxCacheEntrySizeBytes() {
        return maxCacheEntrySizeBytes;
    }

    /**
     * Whether pre-compressed files should be served.
     */
    public boolean serveCompressedFiles() {
        return serveCompressedFiles;
    }

    @Override
    public String toString() {
        return toString(this, vfs(), clock(), maxCacheEntries(), maxCacheEntrySizeBytes());
    }

    static String toString(Object holder, HttpVfs vfs, Clock clock,
                           int maxCacheEntries, int maxCacheEntrySizeBytes) {

        return holder.getClass().getSimpleName() +
               "(vfs: " + vfs +
               ", clock: " + clock +
               ", maxCacheEntries: " + maxCacheEntries +
               ", maxCacheEntrySizeBytes: " + maxCacheEntrySizeBytes + ')';
    }
}
