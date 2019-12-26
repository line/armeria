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

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map.Entry;

import com.linecorp.armeria.common.CacheControl;

/**
 * Builds a new {@link FileService} and its {@link FileServiceConfig}. Use the factory methods in
 * {@link FileService} if you do not override the default settings.
 *
 * @deprecated Use {@link FileServiceBuilder}.
 */
@Deprecated
public class HttpFileServiceBuilder extends FileServiceBuilder {

    /**
     * Creates a new {@link FileServiceBuilder} with the specified {@code rootDir} in an O/S file system.
     *
     * @deprecated Use {@link FileServiceBuilder#forFileSystem(String)}.
     */
    @Deprecated
    public static HttpFileServiceBuilder forFileSystem(String rootDir) {
        return forVfs(HttpVfs.ofFileSystem(rootDir));
    }

    /**
     * Creates a new {@link FileServiceBuilder} with the specified {@code rootDir} in an O/S file system.
     *
     * @deprecated Use {@link FileServiceBuilder#forFileSystem(Path)}.
     */
    @Deprecated
    public static HttpFileServiceBuilder forFileSystem(Path rootDir) {
        return forVfs(HttpVfs.ofFileSystem(rootDir));
    }

    /**
     * Creates a new {@link FileServiceBuilder} with the specified {@code rootDir} in the current class
     * path.
     *
     * @deprecated Use {@link FileServiceBuilder#forClassPath(String)}.
     */
    @Deprecated
    public static HttpFileServiceBuilder forClassPath(String rootDir) {
        return forVfs(HttpVfs.ofClassPath(rootDir));
    }

    /**
     * Creates a new {@link FileServiceBuilder} with the specified {@code rootDir} in the current class
     * path.
     *
     * @deprecated Use {@link FileServiceBuilder#forClassPath(ClassLoader, String)}.
     */
    @Deprecated
    public static HttpFileServiceBuilder forClassPath(ClassLoader classLoader, String rootDir) {
        return forVfs(HttpVfs.ofClassPath(classLoader, rootDir));
    }

    /**
     * Creates a new {@link FileServiceBuilder} with the specified {@link HttpVfs}.
     *
     * @deprecated Use {@link FileServiceBuilder#forVfs(HttpVfs)}.
     */
    @Deprecated
    public static HttpFileServiceBuilder forVfs(HttpVfs vfs) {
        return new HttpFileServiceBuilder(vfs);
    }

    HttpFileServiceBuilder(HttpVfs vfs) {
        super(vfs);
    }

    @Override
    public HttpFileServiceBuilder clock(Clock clock) {
        return (HttpFileServiceBuilder) super.clock(clock);
    }

    @Override
    public HttpFileServiceBuilder maxCacheEntries(int maxCacheEntries) {
        return (HttpFileServiceBuilder) super.maxCacheEntries(maxCacheEntries);
    }

    @Override
    public HttpFileServiceBuilder entryCacheSpec(String entryCacheSpec) {
        return (HttpFileServiceBuilder) super.entryCacheSpec(entryCacheSpec);
    }

    @Override
    public HttpFileServiceBuilder serveCompressedFiles(boolean serveCompressedFiles) {
        return (HttpFileServiceBuilder) super.serveCompressedFiles(serveCompressedFiles);
    }

    @Override
    public HttpFileServiceBuilder maxCacheEntrySizeBytes(int maxCacheEntrySizeBytes) {
        return (HttpFileServiceBuilder) super.maxCacheEntrySizeBytes(maxCacheEntrySizeBytes);
    }

    @Override
    public HttpFileServiceBuilder autoIndex(boolean autoIndex) {
        return (HttpFileServiceBuilder) super.autoIndex(autoIndex);
    }

    @Override
    public HttpFileServiceBuilder addHeader(CharSequence name, Object value) {
        return (HttpFileServiceBuilder) super.addHeader(name, value);
    }

    @Override
    public HttpFileServiceBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (HttpFileServiceBuilder) super.addHeaders(headers);
    }

    @Override
    public HttpFileServiceBuilder setHeader(CharSequence name, Object value) {
        return (HttpFileServiceBuilder) super.setHeader(name, value);
    }

    @Override
    public HttpFileServiceBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (HttpFileServiceBuilder) super.setHeaders(headers);
    }

    @Override
    public HttpFileServiceBuilder cacheControl(CacheControl cacheControl) {
        return (HttpFileServiceBuilder) super.cacheControl(cacheControl);
    }

    @Override
    public HttpFileServiceBuilder cacheControl(CharSequence cacheControl) {
        return (HttpFileServiceBuilder) super.cacheControl(cacheControl);
    }

    /**
     * Returns a newly-created {@link HttpFileService} based on the properties of this builder.
     */
    @Override
    public HttpFileService build() {
        return new HttpFileService(new FileServiceConfig(
                vfs, clock, entryCacheSpec, maxCacheEntrySizeBytes,
                serveCompressedFiles, autoIndex, buildHeaders()));
    }
}
