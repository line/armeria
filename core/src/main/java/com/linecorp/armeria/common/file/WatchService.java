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

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * A registry which wraps a {@link java.nio.file.WatchService} and allows paths to be registered.
 */
@UnstableApi
public final class WatchService implements AutoCloseable {

    private final Map<FileSystem, AsyncWatchService> fileSystemWatchServiceMap =
            new HashMap<>();
    private final ReentrantLock lock = new ReentrantShortLock();

    /**
     * Registers a {@code path} and {@code callback} to the {@link java.nio.file.WatchService}. When the
     * contents of the registered file are changed, then the {@code callback} function
     * is invoked. This method is thread safe.
     * @param path the directory path that which will be watched for changes.
     * @param callback the function which is invoked when the file is changed.
     *
     * @return a key which is used to unregister from watching.
     */
    public WatchKey register(Path path, DirectoryWatcher callback) {
        final Path normalizedPath = path.toAbsolutePath().normalize();
        lock.lock();
        try {
            final AsyncWatchService watchServiceContext = fileSystemWatchServiceMap.computeIfAbsent(
                    path.getFileSystem(), fileSystem -> new AsyncWatchService(
                            "armeria-file-watcher-" + fileSystem.getClass().getName(),
                            fileSystem));
            final WatchKey key = new WatchKey(normalizedPath, this, callback);
            watchServiceContext.register(path, key);
            return key;
        } finally {
            lock.unlock();
        }
    }

    void unregister(WatchKey watchKey) {
        lock.lock();
        try {
            final FileSystem fileSystem = watchKey.path().getFileSystem();
            final AsyncWatchService watchServiceContext = fileSystemWatchServiceMap.get(fileSystem);
            if (watchServiceContext == null) {
                return;
            }
            watchServiceContext.unregister(watchKey);
            if (!watchServiceContext.hasWatchers()) {
                watchServiceContext.close();
                fileSystemWatchServiceMap.remove(fileSystem);
            }
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    boolean hasWatchers() {
        return fileSystemWatchServiceMap.values().stream().anyMatch(AsyncWatchService::hasWatchers);
    }

    @Override
    public void close() throws Exception {
        lock.lock();
        try {
            for (AsyncWatchService context : fileSystemWatchServiceMap.values()) {
                context.close();
            }
            fileSystemWatchServiceMap.clear();
        } finally {
            lock.unlock();
        }
    }
}
