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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.endpoint.FileWatcherRunnable.FileWatchEvent;

/**
 * Wraps a {@link WatchService} and allows paths to be registered.
 */
final class FileWatcherRegistry implements AutoCloseable {

    /**
     * Context responsible for watching a {@link FileSystem}. Contains a reference to
     * a {@link WatchService} and a {@link Thread} which continuously watches for changes
     * in registered file paths.
     */
    private static class FileWatchServiceContext {

        private final RestartableThread restartableThread;
        private final WatchService watchService;
        private final Map<FileWatcherEventKey, FileWatchEvent> currWatchEventMap =
                new ConcurrentHashMap<>();

        /**
         * Creates a new {@link FileWatchServiceContext}.
         * @param name the name of the thread used to watch for changes
         * @param watchService the {@link WatchService} used to monitor for changes
         */
        FileWatchServiceContext(String name, WatchService watchService) {
            restartableThread = new RestartableThread(name, () ->
                    new FileWatcherRunnable(watchService, currWatchEventMap::values));
            this.watchService = watchService;
        }

        /**
         * Starts to watch changes for the path corresponding to the {@link FileWatcherEventKey}.
         * When changes are detected, the {@code reloader} is invoked. If the {@link WatchService}
         * isn't running yet, this method starts a thread to start watching for the {@link FileSystem}.
         * @param watcherEventKey key that contains the path to be watched
         * @param reloader function invoked on file change
         *
         * @throws IllegalArgumentException if failed to locate file or failed to start watching
         */
        synchronized void register(FileWatcherEventKey watcherEventKey, Runnable reloader) {
            final Path dirPath;
            try {
                dirPath = watcherEventKey.getFilePath().toRealPath().getParent();
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to locate file " + watcherEventKey.getFilePath(), e);
            }

            final WatchKey watchKey;
            try {
                watchKey = dirPath.register(watchService,
                                            ENTRY_CREATE,
                                            ENTRY_MODIFY,
                                            ENTRY_DELETE,
                                            OVERFLOW);
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to watch file " + watcherEventKey.getFilePath(), e);
            }
            currWatchEventMap.put(watcherEventKey, new FileWatchEvent(watchKey, reloader, dirPath));
            if (!currWatchEventMap.isEmpty()) {
                restartableThread.start();
            }
        }

        /**
         * Deregisters a {@link FileWatcherEventKey}. On changes to the {@code path} corresponding
         * to the {@link FileWatcherEventKey}, the {@code reloader} won't be invoked anymore. If
         * no paths are watched by the {@link WatchService}, then the background thread is stopped.
         * @param watcherEventKey key for which the {@link WatchService} will stop watching.
         */
        synchronized void deregister(FileWatcherEventKey watcherEventKey) {
            if (!currWatchEventMap.containsKey(watcherEventKey)) {
                return;
            }
            final FileWatchEvent fileWatchEvent = currWatchEventMap.remove(watcherEventKey);
            final boolean existsDirWatcher = currWatchEventMap.values().stream().anyMatch(
                    value -> value.path().equals(fileWatchEvent.path()));
            if (!existsDirWatcher) {
                fileWatchEvent.cancel();
            }
            if (currWatchEventMap.isEmpty()) {
                restartableThread.stop();
            }
        }

        /**
         * Whether a background thread for watching changes is running.
         * @return true if the thread is running.
         */
        boolean isRunning() {
            return restartableThread.isRunning();
        }

        void close() throws IOException {
            currWatchEventMap.clear();
            restartableThread.stop();
            watchService.close();
        }
    }

    private final Map<FileSystem, FileWatchServiceContext> watchServiceContextMap = new ConcurrentHashMap<>();

    /**
     * Registers a {@code watchEventKey} and {@code reloader} to the {@link WatchService}. When the
     * file of the path of the {@code watchEventKey} is changed, then the {@code reloader} function
     * is invoked. If the {@code watchEventKey} is already registered, then nothing happens.
     * This method is thread safe.
     * @param watchEventKey the key which is registered.
     * @param reloader function which is invoked when file is changed.
     */
    synchronized void register(FileWatcherEventKey watchEventKey, Runnable reloader) {
        final FileWatchServiceContext watchServiceContext = watchServiceContextMap.computeIfAbsent(
                watchEventKey.getFilePath().getFileSystem(), fileSystem -> {
                    try {
                        return new FileWatchServiceContext("file-watcher-" + fileSystem.getClass().getName(),
                                                           fileSystem.newWatchService());
                    } catch (IOException e) {
                        throw new IllegalArgumentException(
                                "invalid filesystem for path: " + watchEventKey.getFilePath());
                    }
                });
        watchServiceContext.register(watchEventKey, reloader);
    }

    /**
     * Stops watching a properties file corresponding to the {@link FileWatcherEventKey}. Nothing
     * happens if the {@link FileWatcherEventKey} is not registered or already deregistered. This
     * method is thread safe.
     * @param watcherEventKey key that was used to register for watching a file.
     */
    synchronized void deregister(FileWatcherEventKey watcherEventKey) {
        final FileSystem fileSystem = watcherEventKey.getFilePath().getFileSystem();
        final FileWatchServiceContext watchServiceContext = watchServiceContextMap.get(fileSystem);
        if (watchServiceContext == null) {
            return;
        }
        watchServiceContext.deregister(watcherEventKey);
        if (!watchServiceContext.isRunning()) {
            watchServiceContextMap.remove(fileSystem);
        }
    }

    /**
     * Returns whether the current registry is watching a file.
     * @return true if a file is watched
     */
    @VisibleForTesting
    boolean isRunning() {
        return watchServiceContextMap.values().stream().anyMatch(FileWatchServiceContext::isRunning);
    }

    /**
     * Close the {@link WatchService}, thread, and clear registry.
     * @throws Exception may be thrown if an I/O error occurs
     */
    @Override
    public void close() throws Exception {
        watchServiceContextMap.values().forEach(context -> {
            try {
                context.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        watchServiceContextMap.clear();
    }

    /**
     * Key used to register for file change events. The key must support a @{code getPath} method
     * which returns the path to be watched.
     */
    interface FileWatcherEventKey {

        /**
         * Returns the file path associated with the current key. The path is used to register and deregister
         * to the {@link WatchService}. The path must be immutable for each {@link FileWatcherEventKey}.
         * @return the path associated with the current key
         */
        Path getFilePath();
    }
}
