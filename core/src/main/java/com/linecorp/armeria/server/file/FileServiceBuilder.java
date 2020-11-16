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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.server.file.FileServiceConfig.validateEntryCacheSpec;
import static com.linecorp.armeria.server.file.FileServiceConfig.validateMaxCacheEntrySizeBytes;
import static com.linecorp.armeria.server.file.FileServiceConfig.validateNonNegativeParameter;
import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.CaffeineSpec;

import com.linecorp.armeria.common.CacheControl;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link FileService} and its {@link FileServiceConfig}. Use the factory methods in
 * {@link FileService} if you do not override the default settings.
 */
public final class FileServiceBuilder {

    @Nullable
    private static final String DEFAULT_ENTRY_CACHE_SPEC = Flags.fileServiceCacheSpec();
    private static final int DEFAULT_MAX_CACHE_ENTRY_SIZE_BYTES = 65536;

    final HttpVfs vfs;
    Clock clock = Clock.systemUTC();
    @Nullable
    String entryCacheSpec = DEFAULT_ENTRY_CACHE_SPEC;
    int maxCacheEntrySizeBytes = DEFAULT_MAX_CACHE_ENTRY_SIZE_BYTES;
    boolean serveCompressedFiles;
    boolean autoIndex;
    boolean canSetMaxCacheEntries = true;
    boolean canSetEntryCacheSpec = true;
    @Nullable
    HttpHeadersBuilder headers;

    FileServiceBuilder(HttpVfs vfs) {
        this.vfs = requireNonNull(vfs, "vfs");
    }

    /**
     * Sets the {@link Clock} that provides the current date and time.
     */
    public FileServiceBuilder clock(Clock clock) {
        this.clock = requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Sets the maximum allowed number of cached file entries. If not set, up to {@code 1024} entries
     * are cached by default.
     */
    public FileServiceBuilder maxCacheEntries(int maxCacheEntries) {
        checkState(canSetMaxCacheEntries,
                   "Cannot call maxCacheEntries() if called entryCacheSpec() already.");
        validateNonNegativeParameter(maxCacheEntries, "maxCacheEntries");
        if (maxCacheEntries == 0) {
            entryCacheSpec = null;
        } else {
            entryCacheSpec = String.format("maximumSize=%d", maxCacheEntries);
        }
        canSetEntryCacheSpec = false;
        return this;
    }

    /**
     * Sets the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the content
     * of the {@link HttpFile}s read by the {@link FileService}.
     * If not set, {@link Flags#fileServiceCacheSpec()} is used by default.
     */
    public FileServiceBuilder entryCacheSpec(String entryCacheSpec) {
        requireNonNull(entryCacheSpec, "entryCacheSpec");
        checkState(canSetEntryCacheSpec,
                   "Cannot call entryCacheSpec() if called maxCacheEntries() already.");
        this.entryCacheSpec = validateEntryCacheSpec(entryCacheSpec);
        canSetMaxCacheEntries = false;
        return this;
    }

    /**
     * Sets whether pre-compressed files should be served. {@link FileService} supports serving files
     * compressed with gzip, with the extension {@code ".gz"}, and brotli, with the extension {@code ".br"}.
     * The extension should be appended to the original file. For example, to serve {@code index.js} either
     * raw, gzip-compressed, or brotli-compressed, there should be three files, {@code index.js},
     * {@code index.js.gz}, and {@code index.js.br}. By default, this feature is disabled.
     *
     * <p>Some tools for precompressing resources during a build process include {@code gulp-zopfli} and
     * {@code gulp-brotli}, which by default create files with the correct extension.
     */
    public FileServiceBuilder serveCompressedFiles(boolean serveCompressedFiles) {
        this.serveCompressedFiles = serveCompressedFiles;
        return this;
    }

    /**
     * Sets the maximum allowed size of a cached file entry. The file bigger than this value will not be
     * cached. If not set, {@value #DEFAULT_MAX_CACHE_ENTRY_SIZE_BYTES} is used by default.
     */
    public FileServiceBuilder maxCacheEntrySizeBytes(int maxCacheEntrySizeBytes) {
        this.maxCacheEntrySizeBytes = validateMaxCacheEntrySizeBytes(maxCacheEntrySizeBytes);
        return this;
    }

    /**
     * Sets whether {@link FileService} auto-generates a directory listing for a directory without an
     * {@code index.html} file. By default, this feature is disabled. Consider the security implications of
     * when enabling this feature.
     */
    public FileServiceBuilder autoIndex(boolean autoIndex) {
        this.autoIndex = autoIndex;
        return this;
    }

    /**
     * Returns the immutable additional {@link HttpHeaders} which will be set when building an
     * {@link HttpResponse}.
     */
    HttpHeaders buildHeaders() {
        return headers != null ? headers.build() : HttpHeaders.of();
    }

    private HttpHeadersBuilder headersBuilder() {
        if (headers == null) {
            headers = HttpHeaders.builder();
        }
        return headers;
    }

    /**
     * Adds the specified HTTP header.
     */
    public FileServiceBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder().addObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Adds the specified HTTP headers.
     */
    public FileServiceBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().addObject(headers);
        return this;
    }

    /**
     * Sets the specified HTTP header.
     */
    public FileServiceBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder().setObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Sets the specified HTTP headers.
     */
    public FileServiceBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().setObject(headers);
        return this;
    }

    /**
     * Sets the {@code "cache-control"} header. This method is a shortcut for:
     * <pre>{@code
     * builder.setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
     * }</pre>
     */
    public FileServiceBuilder cacheControl(CacheControl cacheControl) {
        requireNonNull(cacheControl, "cacheControl");
        return setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
    }

    /**
     * Sets the {@code "cache-control"} header. This method is a shortcut for:
     * <pre>{@code
     * builder.setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
     * }</pre>
     */
    public FileServiceBuilder cacheControl(CharSequence cacheControl) {
        requireNonNull(cacheControl, "cacheControl");
        return setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
    }

    /**
     * Returns a newly-created {@link FileService} based on the properties of this builder.
     */
    public FileService build() {
        return new FileService(new FileServiceConfig(
                vfs, clock, entryCacheSpec, maxCacheEntrySizeBytes,
                serveCompressedFiles, autoIndex, buildHeaders()));
    }

    @Override
    public String toString() {
        return FileServiceConfig.toString(this, vfs, clock, entryCacheSpec, maxCacheEntrySizeBytes,
                                          serveCompressedFiles, autoIndex, headers);
    }
}
