/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.endpoint.FileWatcherRunnable.FileWatchEvent;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * A registry which wraps a {@link WatchService} and allows paths to be registered.
 */
final class FileWatcherRegistry implements AutoCloseable {

    /**
     * A context responsible for watching a {@link FileSystem}. It contains references to
     * a {@link WatchService} and a {@link Thread} which continuously watches for changes
     * in registered file paths.
     */
    static class FileSystemWatchContext {

        private final RestartableThread restartableThread;
        private final WatchService watchService;
        private final Map<FileWatchRegisterKey, FileWatchEvent> currWatchEventMap =
                new ConcurrentHashMap<>();

        /**
         * Creates a new {@link FileSystemWatchContext}.
         * @param name the name of the thread used to watch for changes
         * @param watchService the {@link WatchService} used to monitor for changes
         */
        FileSystemWatchContext(String name, WatchService watchService) {
            restartableThread = new RestartableThread(name, () ->
                    new FileWatcherRunnable(watchService, this));
            this.watchService = watchService;
        }

        /**
         * Starts to watch changes for the path corresponding to the {@link FileWatchRegisterKey}.
         * When changes are detected, the {@code callback} is invoked. If a {@link WatchService}
         * isn't running yet, this method starts a thread to start watching the {@link FileSystem}.
         * @param watchRegisterKey the key that contains the path to be watched
         * @param callback the function invoked on file change
         *
         * @throws IllegalArgumentException if failed to locate file or failed to start watching
         */
        private void register(FileWatchRegisterKey watchRegisterKey, Runnable callback) {
            final Path dirPath = watchRegisterKey.filePath().getParent();
            checkArgument(dirPath != null, "no parent directory for input path: %s",
                          watchRegisterKey.filePath());

            final WatchKey watchKey;
            try {
                watchKey = dirPath.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE, OVERFLOW);
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to watch a file " + watchRegisterKey.filePath(), e);
            }
            currWatchEventMap.put(watchRegisterKey, new FileWatchEvent(watchKey, callback, dirPath));
            restartableThread.start();
        }

        /**
         * Unregisters a {@link FileWatchRegisterKey}. The {@code callback} won't be invoked anymore when the
         * contents of the file for the {@code filePath} is changed. If no paths are watched by the
         * {@link WatchService}, then the background thread is stopped.
         * @param watchRegisterKey the key for which the {@link WatchService} will stop watching.
         */
        private void unregister(FileWatchRegisterKey watchRegisterKey) {
            if (!currWatchEventMap.containsKey(watchRegisterKey)) {
                return;
            }
            final FileWatchEvent fileWatchEvent = currWatchEventMap.remove(watchRegisterKey);
            final boolean existsDirWatcher = currWatchEventMap.values().stream().anyMatch(
                    value -> value.dirPath().equals(fileWatchEvent.dirPath()));
            if (!existsDirWatcher) {
                fileWatchEvent.cancel();
            }
            if (currWatchEventMap.isEmpty()) {
                restartableThread.stop();
            }
        }

        /**
         * Whether a background thread for watching changes is running.
         * @return {@code true} if the thread is running.
         */
        boolean isRunning() {
            return restartableThread.isRunning();
        }

        Collection<FileWatchEvent> watchEvents() {
            return currWatchEventMap.values();
        }

        void close() throws IOException {
            currWatchEventMap.clear();
            restartableThread.stop();
            watchService.close();
        }
    }

    private final Map<FileSystem, FileSystemWatchContext> fileSystemWatchServiceMap =
            new HashMap<>();
    private final ReentrantLock lock = new ReentrantShortLock();

    /**
     * Registers a {@code filePath} and {@code callback} to the {@link WatchService}. When the
     * contents of the registered file is changed, then the {@code callback} function
     * is invoked. If the {@code watchEventKey} is already registered, then nothing happens.
     * This method is thread safe.
     * @param filePath the path of the file which will be watched for changes.
     * @param callback the function which is invoked when the file is changed.
     *
     * @return a key which is used to unregister from watching.
     */
    FileWatchRegisterKey register(Path filePath, Runnable callback) {
        lock();
        try {
            final FileWatchRegisterKey watchRegisterKey = new FileWatchRegisterKey(filePath);
            final FileSystemWatchContext watchServiceContext = fileSystemWatchServiceMap.computeIfAbsent(
                    filePath.getFileSystem(), fileSystem -> {
                        try {
                            return new FileSystemWatchContext(
                                    "armeria-file-watcher-" + fileSystem.getClass().getName(),
                                    fileSystem.newWatchService());
                        } catch (IOException e) {
                            throw new IllegalArgumentException(
                                    "failed to create a new watch service for the path: " +
                                    watchRegisterKey.filePath(), e);
                        }
                    });
            watchServiceContext.register(watchRegisterKey, callback);
            return watchRegisterKey;
        } finally {
            unlock();
        }
    }

    /**
     * Stops watching a properties file corresponding to the {@link FileWatchRegisterKey}. Nothing
     * happens if the {@link FileWatchRegisterKey} is not registered or already unregistered. This
     * method is thread safe.
     * @param watchRegisterKey the key that was used to register for watching a file.
     */
    void unregister(FileWatchRegisterKey watchRegisterKey) {
        lock();
        try {
            final FileSystem fileSystem = watchRegisterKey.filePath().getFileSystem();
            final FileSystemWatchContext watchServiceContext = fileSystemWatchServiceMap.get(fileSystem);
            if (watchServiceContext == null) {
                return;
            }
            watchServiceContext.unregister(watchRegisterKey);
            if (!watchServiceContext.isRunning()) {
                fileSystemWatchServiceMap.remove(fileSystem);
            }
        } finally {
            unlock();
        }
    }

    /**
     * Returns whether the current registry is watching a file.
     * @return {@code true} if a file is watched
     */
    @VisibleForTesting
    boolean isRunning() {
        return fileSystemWatchServiceMap.values().stream().anyMatch(FileSystemWatchContext::isRunning);
    }

    /**
     * Closes the {@link WatchService}, thread, and registry.
     * @throws Exception may be thrown if an I/O error occurs
     */
    @Override
    public void close() throws Exception {
        lock();
        try {
            fileSystemWatchServiceMap.values().forEach(context -> {
                try {
                    context.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            fileSystemWatchServiceMap.clear();
        } finally {
            unlock();
        }
    }

    /**
     * A key representing the registration to watch file events for a {@code filePath}. The
     * key is later used to unregister. Key comparison is done via identity
     * comparison to allow duplicate path registration.
     */
    static final class FileWatchRegisterKey {
        private final Path filePath;

        private FileWatchRegisterKey(Path filePath) {
            this.filePath = filePath;
        }

        /**
         * Returns the file path associated with the current key. The path is used to unregister
         * from the {@link WatchService}.
         *
         * @return the path associated with the current key
         */
        Path filePath() {
            return filePath;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("filePath", filePath).toString();
        }
    }

    private void lock() {
        lock.lock();
    }

    private void unlock() {
        lock.unlock();
    }
}
