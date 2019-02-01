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

package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;

import javax.annotation.Nullable;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * A virtual file system that provides the files requested by {@link HttpFileService}.
 */
public interface HttpVfs {

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in an O/S file system.
     */
    static HttpVfs ofFileSystem(String rootDir) {
        return new FileSystemHttpVfs(Paths.get(requireNonNull(rootDir, "rootDir")));
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in an O/S file system.
     */
    static HttpVfs ofFileSystem(Path rootDir) {
        return new FileSystemHttpVfs(rootDir);
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in the current class path.
     */
    static HttpVfs ofClassPath(String rootDir) {
        return ofClassPath(HttpVfs.class.getClassLoader(), rootDir);
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in the current class path.
     */
    static HttpVfs ofClassPath(ClassLoader classLoader, String rootDir) {
        return new ClassPathHttpVfs(classLoader, rootDir);
    }

    /**
     * Finds the file at the specified {@code path}.
     *
     * @param path an absolute path whose component separator is {@code '/'}
     * @param clock the {@link Clock} which provides the current date and time
     * @param contentEncoding the desired {@code 'content-encoding'} header value of the file.
     *                        {@code null} to omit the header.
     *
     * @return the {@link HttpFile} at the specified {@code path}
     */
    HttpFile get(String path, Clock clock, @Nullable String contentEncoding);

    /**
     * Returns whether the file at the specified {@code path} is a directory.
     *
     * @param path an absolute path whose component separator is {@code '/'}.
     * @return {@code true} if the file is a directory. {@code false} if the directory does not exist or
     *         the file listing is not available.
     */
    boolean canList(String path);

    /**
     * Lists the files at the specified directory {@code path} non-recursively.
     *
     * @param path an absolute path whose component separator is {@code '/'}.
     * @return the list of the file names. If the file is a directory, the file name will end with
     *         {@code '/'}. If the directory does not exist or the file listing is not available,
     *         an empty {@link List} is returned.
     */
    List<String> list(String path);

    /**
     * Returns the value of the {@code "vfs"} {@link Tag} in a {@link Meter}.
     */
    String meterTag();
}
