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
import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Wraps a {@code WatchService} and allows paths to be registered.
 */
final class PropertiesFileWatcherRegistry implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PropertiesFileWatcherRegistry.class);

    @Nullable
    private CompletableFuture<Void> future;
    private final Map<Path, PropertiesFileWatcherContext> ctxRegistry = new ConcurrentHashMap<>();
    private final WatchService watchService;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).build());

    /**
     * Create a registry using the default file system.
     */
    PropertiesFileWatcherRegistry() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Registers a {@code resourceUrl} of the properties file to be watched.
     * @param resourceUrl url to be watched
     * @param reloader callback to be called on a file change event
     */
    void register(URL resourceUrl, Runnable reloader) {
        final File file = new File(resourceUrl.getFile());

        synchronized (this) {
            checkArgument(!ctxRegistry.containsKey(getRealPath(resourceUrl)),
                          "file is already watched: %s", resourceUrl.getFile());
            try {
                final Path parentPath = file.getParentFile().toPath();
                final WatchKey key = parentPath.register(watchService,
                                                         ENTRY_CREATE,
                                                         ENTRY_MODIFY,
                                                         ENTRY_DELETE,
                                                         OVERFLOW);
                ctxRegistry.put(getRealPath(resourceUrl), new PropertiesFileWatcherContext(key, reloader));
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to watch file " + resourceUrl.getFile(), e);
            }
            startFutureIfPossible();
        }
    }

    /**
     * Stops watching a properties file corresponding to the {@code resourceUrl}.
     * @param resourceUrl url to stop watching
     */
    void deregister(URL resourceUrl) {
        final File file = new File(resourceUrl.getFile());
        final Path parentPath = file.getParentFile().toPath();

        synchronized (this) {
            if (!ctxRegistry.containsKey(getRealPath(resourceUrl))) {
                return;
            }
            final PropertiesFileWatcherContext context = ctxRegistry.remove(getRealPath(resourceUrl));
            final boolean existsTargetFiles = ctxRegistry.keySet().stream().anyMatch(
                    key -> key.startsWith(parentPath));
            if (!existsTargetFiles) {
                context.key.cancel();
            }
            stopFutureIfPossible();
        }
    }

    private void startFutureIfPossible() {
        if (!isRunning() && !ctxRegistry.isEmpty()) {
            future = CompletableFuture.runAsync(new PropertiesFileWatcherRunnable(), executor);
        }
    }

    private void stopFutureIfPossible() {
        checkState(future != null, "tried to stop null future");
        if (isRunning() && ctxRegistry.isEmpty()) {
            future.cancel(true);
        }
    }

    private static Path getRealPath(URL url) {
        return getRealPath(new File(url.getFile()));
    }

    private static Path getRealPath(File file) {
        return getRealPath(file.toPath());
    }

    private static Path getRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to locate file " + path, e);
        }
    }

    /**
     * Check if future for {@code WatchService} is running.
     * @return true if future is running
     */
    @VisibleForTesting
    boolean isRunning() {
        return future != null && !future.isDone();
    }

    /**
     * Close watch service, future, and clear registry.
     * @throws Exception may be thrown if {@code WatchService} is already closed
     */
    @Override
    public void close() throws Exception {
        ctxRegistry.clear();
        stopFutureIfPossible();
        watchService.close();
    }

    private class PropertiesFileWatcherRunnable implements Runnable {
        @Override
        public void run() {
            try {
                WatchKey key;
                while ((key = watchService.take()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        @SuppressWarnings("unchecked")
                        final Path watchedPath = getRealPath(
                                ((Path) key.watchable()).resolve(((WatchEvent<Path>) event).context()));

                        if (event.kind().equals(ENTRY_MODIFY) || event.kind().equals(ENTRY_CREATE)) {
                            ctxRegistry.entrySet().stream()
                                       .filter(entry -> entry.getKey().startsWith(watchedPath))
                                       .forEach(entry -> {
                                try {
                                    entry.getValue().reloader.run();
                                } catch (Exception e) {
                                    logger.warn("unexpected error from listener: {} ", watchedPath, e);
                                }
                            });
                        } else if (event.kind().equals(OVERFLOW)) {
                            logger.info("failed to reload file: {}", watchedPath);
                        } else if (event.kind().equals(ENTRY_DELETE)) {
                            logger.warn("ignoring deleted file: {}", watchedPath);
                        }
                    }

                    final boolean reset = key.reset();
                    if (!reset) {
                        logger.warn("aborting reload properties file due to unexpected error");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("unexpected interruption while reloading properties file: ", e);
            } catch (ClosedWatchServiceException e) {
                // do nothing
            }
        }
    }

    private static class PropertiesFileWatcherContext {
        private final WatchKey key;
        private final Runnable reloader;

        PropertiesFileWatcherContext(WatchKey key, Runnable reloader) {
            this.key = key;
            this.reloader = reloader;
        }
    }
}
