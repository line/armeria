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

import java.nio.file.Path;
import java.time.Clock;

/**
 * Builds a new {@link HttpFileService} and its {@link HttpFileServiceConfig}. Use the factory methods in
 * {@link HttpFileService} if you do not override the default settings.
 */
public final class HttpFileServiceBuilder {

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
    private int maxCacheEntries = 1024;
    private int maxCacheEntrySizeBytes = 65536;

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
     * Sets the maximum allowed number of cached file entries.
     */
    public HttpFileServiceBuilder maxCacheEntries(int maxCacheEntries) {
        this.maxCacheEntries = HttpFileServiceConfig.validateMaxCacheEntries(maxCacheEntries);
        return this;
    }

    /**
     * Returns the maximum allowed size of a cached file entry. The file bigger than this value will not be
     * cached.
     */
    public HttpFileServiceBuilder maxCacheEntrySizeBytes(int maxCacheEntrySizeBytes) {
        this.maxCacheEntrySizeBytes =
                HttpFileServiceConfig.validateMaxCacheEntrySizeBytes(maxCacheEntrySizeBytes);
        return this;
    }

    public HttpFileService build() {
        return new HttpFileService(new HttpFileServiceConfig(
                vfs, clock, maxCacheEntries, maxCacheEntrySizeBytes));
    }

    @Override
    public String toString() {
        return HttpFileServiceConfig.toString(this, vfs, clock, maxCacheEntries, maxCacheEntrySizeBytes);
    }
}
