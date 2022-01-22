/*
 * Copyright 2020 LINE Corporation
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal {@link HttpVfs} implementation for accessing file system with blocking I/O.
 * All its operations are executed in the given {@code fileReadExecutor} via the blocking I/O methods,
 * such as {@link #blockingGet(Executor, String, Clock, String, HttpHeaders, MediaTypeResolver)}.
 */
public abstract class AbstractBlockingHttpVfs extends AbstractHttpVfs {

    private final boolean directoryListingSupported;

    /**
     * Creates a new instance.
     *
     * @param directoryListingSupported whether this {@link HttpVfs} supports directory listing.
     *                                  If {@code false}, {@link #blockingCanList(Executor, String)} and
     *                                  {@link #blockingList(Executor, String)} will never be invoked.
     */
    protected AbstractBlockingHttpVfs(boolean directoryListingSupported) {
        this.directoryListingSupported = directoryListingSupported;
    }

    /**
     * {@inheritDoc} This method invokes {@link #blockingGet(Executor, String, Clock, String, HttpHeaders,
     * MediaTypeResolver)} from the specified {@code fileReadExecutor}.
     *
     * @deprecated Use {@link #get(Executor, String, Clock, String, HttpHeaders, MediaTypeResolver)} instead.
     */
    @Deprecated
    @Override
    public final HttpFile get(
            Executor fileReadExecutor, String path, Clock clock,
            @Nullable String contentEncoding, HttpHeaders additionalHeaders) {
        return get(fileReadExecutor, path, clock, contentEncoding, additionalHeaders,
                   MediaTypeResolver.ofDefault());
    }

    /**
     * {@inheritDoc} This method invokes {@link #blockingGet(Executor, String, Clock, String, HttpHeaders,
     * MediaTypeResolver)} from the specified {@code fileReadExecutor}.
     */
    @Override
    public final HttpFile get(
            Executor fileReadExecutor, String path, Clock clock,
            @Nullable String contentEncoding, HttpHeaders additionalHeaders,
            MediaTypeResolver mediaTypeResolver) {

        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(path, "path");
        requireNonNull(clock, "clock");
        requireNonNull(additionalHeaders, "additionalHeaders");

        return HttpFile.from(CompletableFuture.supplyAsync(
                () -> blockingGet(fileReadExecutor, path, clock,
                                  contentEncoding, additionalHeaders, mediaTypeResolver), fileReadExecutor));
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
     * @deprecated Use {@link #blockingGet(Executor, String, Clock, String, HttpHeaders, MediaTypeResolver)}
     *     instead.
     */
    @Deprecated
    protected HttpFile blockingGet(Executor fileReadExecutor, String path, Clock clock,
                                   @Nullable String contentEncoding, HttpHeaders additionalHeaders) {
        return blockingGet(fileReadExecutor, path, clock, contentEncoding, additionalHeaders,
                           MediaTypeResolver.ofDefault());
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
     * @param mediaTypeResolver the {@link MediaTypeResolver} to determine the {@link MediaType}.
     * @return the {@link HttpFile} at the specified {@code path}
     */
    protected abstract HttpFile blockingGet(Executor fileReadExecutor, String path, Clock clock,
                                            @Nullable String contentEncoding, HttpHeaders additionalHeaders,
                                            MediaTypeResolver mediaTypeResolver);

    /**
     * {@inheritDoc} This method invokes {@link #blockingCanList(Executor, String)} from the specified
     * {@code fileReadExecutor}.
     */
    @Override
    public final CompletableFuture<Boolean> canList(Executor fileReadExecutor, String path) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(path, "path");

        if (directoryListingSupported) {
            return CompletableFuture.supplyAsync(() -> blockingCanList(fileReadExecutor, path),
                                                 fileReadExecutor);
        } else {
            return super.canList(fileReadExecutor, path);
        }
    }

    /**
     * Returns whether the file at the specified {@code path} is a listable directory. This method returns
     * {@code false} by default.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param path an absolute path that starts with {@code '/'}, whose component separator is {@code '/'}
     * @return {@code true} if the file is a listable directory. {@code false} if the directory does not exist
     *         or the file listing is not available.
     */
    protected boolean blockingCanList(Executor fileReadExecutor, String path) {
        return false;
    }

    /**
     * {@inheritDoc} This method invokes {@link #blockingList(Executor, String)} from the specified
     * {@code fileReadExecutor}.
     */
    @Override
    public final CompletableFuture<List<String>> list(Executor fileReadExecutor, String path) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(path, "path");

        if (directoryListingSupported) {
            return CompletableFuture.supplyAsync(() -> blockingList(fileReadExecutor, path),
                                                 fileReadExecutor);
        } else {
            return super.list(fileReadExecutor, path);
        }
    }

    /**
     * Lists the files at the specified directory {@code path} non-recursively. This method returns an empty
     * list by default.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param path an absolute path that starts with {@code '/'}, whose component separator is {@code '/'}
     * @return the list of the file names. If the file is a directory, the file name will end with
     *         {@code '/'}. If the directory does not exist or the file listing is not available,
     *         an empty {@link List} is returned.
     */
    protected List<String> blockingList(Executor fileReadExecutor, String path) {
        return ImmutableList.of();
    }
}
