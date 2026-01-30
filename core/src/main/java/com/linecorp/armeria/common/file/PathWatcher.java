/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.file;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A callback that is invoked when a file or directory watch event occurs.
 * Use this interface to respond to file system changes detected by a {@link DirectoryWatchService}.
 */
@UnstableApi
@FunctionalInterface
public interface PathWatcher {

    /**
     * Invoked when a watch event occurs on the registered directory or file.
     *
     * @param dirPath the directory path being watched
     * @param filePath the file path that triggered the watch if exists
     * @param event the {@link WatchEvent} that occurred
     */
    void onEvent(Path dirPath, @Nullable Path filePath, WatchEvent<?> event);

    /**
     * Creates a caching {@link PathWatcher} that reads the file content when the watched directory
     * changes and invokes the callback with the file bytes. The callback caches the last modified time
     * to avoid redundant reads when the file hasn't changed. Note that the watched directory is not
     * necessarily the parent directory of the file path.
     *
     * @param filePath the path to the file to read
     * @param callback the consumer to invoke with the file content as bytes
     * @param executor an executor used to read the file
     * @return a new caching {@link PathWatcher}
     */
    static PathWatcher ofFile(Path filePath, Consumer<byte[]> callback,
                              Executor executor) {
        requireNonNull(filePath, "filePath");
        requireNonNull(callback, "callback");
        requireNonNull(executor, "executor");
        return new FileWatcher(filePath, callback, executor);
    }

    /**
     * Creates a caching {@link PathWatcher} that reads the file content when the watched directory
     * changes and invokes the callback with the file bytes. The callback caches the last modified time
     * to avoid redundant reads when the file hasn't changed. Note that the watched directory is not
     * necessarily the parent directory of the file path.
     *
     * @param filePath the path to the file to read
     * @param callback the consumer to invoke with the file content as bytes
     * @return a new caching {@link PathWatcher}
     */
    static PathWatcher ofFile(Path filePath, Consumer<byte[]> callback) {
        requireNonNull(filePath, "filePath");
        requireNonNull(callback, "callback");
        return new FileWatcher(filePath, callback, CommonPools.blockingTaskExecutor());
    }
}
