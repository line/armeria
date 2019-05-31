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
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.endpoint.FileWatcherRunnable.FileWatcherContext;

/**
 * Wraps a {@link WatchService} and allows paths to be registered.
 */
final class PropertiesFileWatcherRegistry implements AutoCloseable {

    private static Path getRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to locate file " + path, e);
        }
    }

    private final Map<PropertiesEndpointGroup, FileWatcherContext> ctxRegistry =
            new ConcurrentHashMap<>();
    private final WatchService watchService;
    private final RestartableThread restartableThread;

    /**
     * Create a registry using the default file system.
     */
    PropertiesFileWatcherRegistry() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        restartableThread =
                new RestartableThread("armeria-file-watcher",
                                      () -> new FileWatcherRunnable(watchService, ctxRegistry::values));
    }

    /**
     * Registers a {@code path} of the properties file to be watched.
     * @param filePath path to be watched
     * @param reloader callback to be called on a file change event
     */
    synchronized void register(PropertiesEndpointGroup group, Path filePath, Runnable reloader) {
        final Path dirPath = getRealPath(filePath).getParent();
        final WatchKey key;
        try {
            key = dirPath.register(watchService,
                                   ENTRY_CREATE,
                                   ENTRY_MODIFY,
                                   ENTRY_DELETE,
                                   OVERFLOW);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to watch file " + filePath, e);
        }
        ctxRegistry.put(group, new FileWatcherContext(key, reloader, dirPath));
        if (!ctxRegistry.isEmpty()) {
            restartableThread.start();
        }
    }

    /**
     * Stops watching a properties file corresponding to the {@code PropertiesEndpointGroup}.
     * @param group group to stop watching
     */
    synchronized void deregister(PropertiesEndpointGroup group) {
        if (!ctxRegistry.containsKey(group)) {
            return;
        }
        final FileWatcherContext removedCtx = ctxRegistry.remove(group);
        final boolean existsDirWatcher = ctxRegistry.values().stream().anyMatch(
                value -> value.path().equals(removedCtx.path()));
        if (!existsDirWatcher) {
            removedCtx.cancel();
        }
        if (ctxRegistry.isEmpty()) {
            restartableThread.stop();
        }
    }

    /**
     * Returns whether the watching thread is running.
     * @return true if future is running
     */
    @VisibleForTesting
    boolean isRunning() {
        return restartableThread.isRunning();
    }

    /**
     * Close the {@link WatchService}, thread, and clear registry.
     * @throws IOException may be thrown if an I/O error occurs
     */
    @Override
    public void close() throws Exception {
        ctxRegistry.clear();
        restartableThread.stop();
        watchService.close();
    }
}
