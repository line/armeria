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

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * A virtual file system that provides the files requested by {@link FileService}.
 */
public interface HttpVfs {

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in an O/S file system.
     */
    static HttpVfs of(File rootDir) {
        return of(requireNonNull(rootDir, "rootDir").toPath());
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in an O/S file system.
     */
    static HttpVfs of(Path rootDir) {
        return new FileSystemHttpVfs(requireNonNull(rootDir, "rootDir"));
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in the current class path.
     */
    static HttpVfs of(ClassLoader classLoader, String rootDir) {
        return new ClassPathHttpVfs(requireNonNull(classLoader, "classLoader"),
                                    requireNonNull(rootDir, "rootDir"));
    }

    /**
     * Finds the file at the specified {@code path}.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param path an absolute path that starts with {@code '/'}, whose component separator is {@code '/'}
     * @param clock the {@link Clock} which provides the current date and time
     * @param contentEncoding the desired {@code 'content-encoding'} header value of the file.
     *                        {@code null} to omit the header.
     * @param additionalHeaders the additional HTTP headers to add to the returned {@link HttpFile}.
     * @return the {@link HttpFile} at the specified {@code path}
     *
     * @deprecated Use {@link #get(Executor, String, Clock, String, HttpHeaders, MediaTypeResolver)} instead.
     */
    @Deprecated
    HttpFile get(Executor fileReadExecutor, String path, Clock clock,
                 @Nullable String contentEncoding, HttpHeaders additionalHeaders);

    /**
     * Finds the file at the specified {@code path}.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param path an absolute path that starts with {@code '/'}, whose component separator is {@code '/'}
     * @param clock the {@link Clock} which provides the current date and time
     * @param contentEncoding the desired {@code 'content-encoding'} header value of the file.
     *                        {@code null} to omit the header.
     * @param additionalHeaders the additional HTTP headers to add to the returned {@link HttpFile}.
     * @param mediaTypeResolver the {@link MediaTypeResolver} to determined {@link MediaType}.
     * @return the {@link HttpFile} at the specified {@code path}
     */
    default HttpFile get(Executor fileReadExecutor, String path, Clock clock,
                 @Nullable String contentEncoding, HttpHeaders additionalHeaders,
                 MediaTypeResolver mediaTypeResolver) {
        return get(fileReadExecutor, path, clock, contentEncoding, additionalHeaders);
    }

    /**
     * Returns whether the file at the specified {@code path} is a listable directory.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param path an absolute path that starts with {@code '/'}, whose component separator is {@code '/'}
     * @return the {@link CompletableFuture} that will be completed with {@code true} if the file is
     *         a listable directory. It will be completed with {@code false} if the directory does not exist
     *         or the file listing is not available.
     */
    CompletableFuture<Boolean> canList(Executor fileReadExecutor, String path);

    /**
     * Lists the files at the specified directory {@code path} non-recursively.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param path an absolute path that starts with {@code '/'}, whose component separator is {@code '/'}
     * @return the {@link CompletableFuture} that will be completed with the list of the file names.
     *         If the file is a directory, the file name will end with {@code '/'}. If the directory does not
     *         exist or the file listing is not available, it will be completed with an empty {@link List}.
     */
    CompletableFuture<List<String>> list(Executor fileReadExecutor, String path);

    /**
     * Returns the value of the {@code "vfs"} {@link Tag} in a {@link Meter}.
     */
    String meterTag();
}
