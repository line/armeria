/*
 * Copyright 2015 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Map.Entry;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * {@link FileService} configuration.
 */
public final class FileServiceConfig {

    private final HttpVfs vfs;
    private final Clock clock;
    @Nullable
    private final String entryCacheSpec;
    private final int maxCacheEntrySizeBytes;
    private final boolean serveCompressedFiles;
    private final boolean autoDecompress;
    private final boolean autoIndex;
    private final HttpHeaders headers;
    private final MediaTypeResolver mediaTypeResolver;

    FileServiceConfig(HttpVfs vfs, Clock clock, @Nullable String entryCacheSpec, int maxCacheEntrySizeBytes,
                      boolean serveCompressedFiles, boolean autoDecompress, boolean autoIndex,
                      HttpHeaders headers, MediaTypeResolver mediaTypeResolver) {
        this.vfs = requireNonNull(vfs, "vfs");
        this.clock = requireNonNull(clock, "clock");
        this.entryCacheSpec = validateEntryCacheSpec(entryCacheSpec);
        this.maxCacheEntrySizeBytes = validateMaxCacheEntrySizeBytes(maxCacheEntrySizeBytes);
        this.serveCompressedFiles = serveCompressedFiles;
        this.autoDecompress = autoDecompress;
        this.autoIndex = autoIndex;
        this.headers = requireNonNull(headers, "headers");
        this.mediaTypeResolver = requireNonNull(mediaTypeResolver, "mediaTypeResolver");
    }

    @Nullable
    static String validateEntryCacheSpec(@Nullable String entryCacheSpec) {
        if (entryCacheSpec == null || "off".equals(entryCacheSpec)) {
            return null;
        }
        try {
            CaffeineSpec.parse(entryCacheSpec);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cache spec: " + entryCacheSpec, e);
        }
        return entryCacheSpec;
    }

    static int validateMaxCacheEntrySizeBytes(int maxCacheEntrySizeBytes) {
        return validateNonNegativeParameter(maxCacheEntrySizeBytes, "maxCacheEntrySizeBytes");
    }

    static int validateNonNegativeParameter(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + ": " + value + " (expected: >= 0)");
        }
        return value;
    }

    /**
     * Returns the {@link HttpVfs} that provides the static files to an {@link FileService}.
     */
    public HttpVfs vfs() {
        return vfs;
    }

    /**
     * Returns the {@link Clock} the provides the current date and time to an {@link FileService}.
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Returns the cache spec of the file entry cache, as defined in {@link CaffeineSpec}.
     */
    @Nullable
    public String entryCacheSpec() {
        return entryCacheSpec;
    }

    /**
     * Returns the maximum allowed size of a cached file entry. Files bigger than this value will not be
     * cached.
     */
    public int maxCacheEntrySizeBytes() {
        return maxCacheEntrySizeBytes;
    }

    /**
     * Returns whether pre-compressed files should be served.
     */
    public boolean serveCompressedFiles() {
        return serveCompressedFiles;
    }

    /**
     * Returns whether pre-compressed files should be automatically decompressed if there is no
     * {@link HttpHeaderNames#ACCEPT_ENCODING} corresponding to the compressed file.
     */
    public boolean autoDecompress() {
        return autoDecompress;
    }

    /**
     * Returns whether a directory listing for a directory without an {@code index.html} file will be
     * auto-generated.
     */
    public boolean autoIndex() {
        return autoIndex;
    }

    /**
     * Returns the additional {@link HttpHeaders} to send in a response.
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Returns the {@link MediaTypeResolver} used for resolving the {@link MediaType} of a file.
     */
    public MediaTypeResolver mediaTypeResolver() {
        return mediaTypeResolver;
    }

    @Override
    public String toString() {
        return toString(this, vfs(), clock(), entryCacheSpec(), maxCacheEntrySizeBytes(),
                        serveCompressedFiles(), autoIndex(), headers(), mediaTypeResolver());
    }

    static String toString(Object holder, HttpVfs vfs, Clock clock,
                           @Nullable String entryCacheSpec, int maxCacheEntrySizeBytes,
                           boolean serveCompressedFiles, boolean autoIndex,
                           @Nullable Iterable<Entry<AsciiString, String>> headers,
                           MediaTypeResolver mediaTypeResolver) {

        return MoreObjects.toStringHelper(holder).omitNullValues()
                          .add("vfs", vfs)
                          .add("clock", clock)
                          .add("entryCacheSpec", entryCacheSpec)
                          .add("maxCacheEntrySizeBytes", maxCacheEntrySizeBytes)
                          .add("serveCompressedFiles", serveCompressedFiles)
                          .add("autoIndex", autoIndex)
                          .add("headers", headers)
                          .add("mediaTypeResolver", mediaTypeResolver)
                          .toString();
    }
}
