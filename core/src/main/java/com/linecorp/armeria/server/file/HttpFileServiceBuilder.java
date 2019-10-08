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
import static com.linecorp.armeria.server.file.HttpFileServiceConfig.validateEntryCacheSpec;
import static com.linecorp.armeria.server.file.HttpFileServiceConfig.validateMaxCacheEntrySizeBytes;
import static com.linecorp.armeria.server.file.HttpFileServiceConfig.validateNonNegativeParameter;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map.Entry;
import java.util.Optional;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.CacheControl;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link HttpFileService} and its {@link HttpFileServiceConfig}. Use the factory methods in
 * {@link HttpFileService} if you do not override the default settings.
 */
public final class HttpFileServiceBuilder {

    private static final Optional<String> DEFAULT_ENTRY_CACHE_SPEC = Flags.fileServiceCacheSpec();
    private static final int DEFAULT_MAX_CACHE_ENTRY_SIZE_BYTES = 65536;

    /**
     * Creates a new {@link HttpFileServiceBuilder} with the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileServiceBuilder forFileSystem(String rootDir) {
        return forVfs(HttpVfs.ofFileSystem(rootDir));
    }

    /**
     * Creates a new {@link HttpFileServiceBuilder} with the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileServiceBuilder forFileSystem(Path rootDir) {
        return forVfs(HttpVfs.ofFileSystem(rootDir));
    }

    /**
     * Creates a new {@link HttpFileServiceBuilder} with the specified {@code rootDir} in the current class
     * path.
     */
    public static HttpFileServiceBuilder forClassPath(String rootDir) {
        return forVfs(HttpVfs.ofClassPath(rootDir));
    }

    /**
     * Creates a new {@link HttpFileServiceBuilder} with the specified {@code rootDir} in the current class
     * path.
     */
    public static HttpFileServiceBuilder forClassPath(ClassLoader classLoader, String rootDir) {
        return forVfs(HttpVfs.ofClassPath(classLoader, rootDir));
    }

    /**
     * Creates a new {@link HttpFileServiceBuilder} with the specified {@link HttpVfs}.
     */
    public static HttpFileServiceBuilder forVfs(HttpVfs vfs) {
        return new HttpFileServiceBuilder(vfs);
    }

    private final HttpVfs vfs;
    private Clock clock = Clock.systemUTC();
    private Optional<String> entryCacheSpec = DEFAULT_ENTRY_CACHE_SPEC;
    private int maxCacheEntrySizeBytes = DEFAULT_MAX_CACHE_ENTRY_SIZE_BYTES;
    private boolean serveCompressedFiles;
    private boolean autoIndex;
    private boolean canSetMaxCacheEntries = true;
    private boolean canSetEntryCacheSpec = true;
    @Nullable
    private HttpHeadersBuilder headers;

    private HttpFileServiceBuilder(HttpVfs vfs) {
        this.vfs = requireNonNull(vfs, "vfs");
    }

    /**
     * Sets the {@link Clock} that provides the current date and time.
     */
    public HttpFileServiceBuilder clock(Clock clock) {
        this.clock = requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Sets the maximum allowed number of cached file entries. If not set, up to {@code 1024} entries
     * are cached by default.
     */
    public HttpFileServiceBuilder maxCacheEntries(int maxCacheEntries) {
        checkState(canSetMaxCacheEntries,
                   "Cannot call maxCacheEntries() if called entryCacheSpec() already.");
        validateNonNegativeParameter(maxCacheEntries, "maxCacheEntries");
        if (maxCacheEntries == 0) {
            entryCacheSpec = Optional.empty();
        } else {
            entryCacheSpec = Optional.of(String.format("maximumSize=%d", maxCacheEntries));
        }
        canSetEntryCacheSpec = false;
        return this;
    }

    /**
     * Sets the cache spec for caching file entries. If not set, {@code "maximumSize=1024"} is used by default.
     */
    public HttpFileServiceBuilder entryCacheSpec(String entryCacheSpec) {
        requireNonNull(entryCacheSpec, "entryCacheSpec");
        checkState(canSetEntryCacheSpec,
                   "Cannot call entryCacheSpec() if called maxCacheEntries() already.");
        this.entryCacheSpec = validateEntryCacheSpec(Optional.of(entryCacheSpec));
        canSetMaxCacheEntries = false;
        return this;
    }

    /**
     * Sets whether pre-compressed files should be served. {@link HttpFileService} supports serving files
     * compressed with gzip, with the extension {@code ".gz"}, and brotli, with the extension {@code ".br"}.
     * The extension should be appended to the original file. For example, to serve {@code index.js} either
     * raw, gzip-compressed, or brotli-compressed, there should be three files, {@code index.js},
     * {@code index.js.gz}, and {@code index.js.br}. By default, this feature is disabled.
     *
     * <p>Some tools for precompressing resources during a build process include {@code gulp-zopfli} and
     * {@code gulp-brotli}, which by default create files with the correct extension.
     */
    public HttpFileServiceBuilder serveCompressedFiles(boolean serveCompressedFiles) {
        this.serveCompressedFiles = serveCompressedFiles;
        return this;
    }

    /**
     * Sets the maximum allowed size of a cached file entry. The file bigger than this value will not be
     * cached. If not set, {@value #DEFAULT_MAX_CACHE_ENTRY_SIZE_BYTES} is used by default.
     */
    public HttpFileServiceBuilder maxCacheEntrySizeBytes(int maxCacheEntrySizeBytes) {
        this.maxCacheEntrySizeBytes = validateMaxCacheEntrySizeBytes(maxCacheEntrySizeBytes);
        return this;
    }

    /**
     * Sets whether {@link HttpFileService} auto-generates a directory listing for a directory without an
     * {@code index.html} file. By default, this feature is disabled. Consider the security implications of
     * when enabling this feature.
     */
    public HttpFileServiceBuilder autoIndex(boolean autoIndex) {
        this.autoIndex = autoIndex;
        return this;
    }

    /**
     * Returns the immutable additional {@link HttpHeaders} which will be set when building an
     * {@link HttpResponse}.
     */
    private HttpHeaders buildHeaders() {
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
    public HttpFileServiceBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder().addObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Adds the specified HTTP headers.
     */
    public HttpFileServiceBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().addObject(headers);
        return this;
    }

    /**
     * Sets the specified HTTP header.
     */
    public HttpFileServiceBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        headersBuilder().setObject(HttpHeaderNames.of(name), value);
        return this;
    }

    /**
     * Sets the specified HTTP headers.
     */
    public HttpFileServiceBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().setObject(headers);
        return this;
    }

    /**
     * Sets the {@code "cache-control"} header. This method is a shortcut of:
     * <pre>{@code
     * builder.setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
     * }</pre>
     */
    public HttpFileServiceBuilder cacheControl(CacheControl cacheControl) {
        requireNonNull(cacheControl, "cacheControl");
        return setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
    }

    /**
     * Sets the {@code "cache-control"} header. This method is a shortcut of:
     * <pre>{@code
     * builder.setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
     * }</pre>
     */
    public HttpFileServiceBuilder cacheControl(CharSequence cacheControl) {
        requireNonNull(cacheControl, "cacheControl");
        return setHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
    }

    /**
     * Returns a newly-created {@link HttpFileService} based on the properties of this builder.
     */
    public HttpFileService build() {
        return new HttpFileService(new HttpFileServiceConfig(
                vfs, clock, entryCacheSpec, maxCacheEntrySizeBytes,
                serveCompressedFiles, autoIndex, buildHeaders()));
    }

    @Override
    public String toString() {
        return HttpFileServiceConfig.toString(this, vfs, clock, entryCacheSpec, maxCacheEntrySizeBytes,
                                              serveCompressedFiles, autoIndex, headers);
    }
}
