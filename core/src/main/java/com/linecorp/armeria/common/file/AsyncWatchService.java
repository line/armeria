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

import static com.linecorp.armeria.common.file.DirectoryWatcher.WATCHER_REGISTERED;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

final class AsyncWatchService implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AsyncWatchService.class);

    private final Thread watchThread;
    private final Thread callbackThread;
    private final WatchService watchService;
    @GuardedBy("lock")
    private final Map<Path, WatchCallbacks> currWatchEventMap = new HashMap<>();
    private final ReentrantLock lock = new ReentrantShortLock();
    private final BlockingDeque<java.nio.file.WatchKey> watchKeys = new LinkedBlockingDeque<>();
    private volatile boolean closed;

    AsyncWatchService(String name, FileSystem fileSystem) {
        try {
            watchService = fileSystem.newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        watchThread = new Thread(this::watchServiceLoop, name + "-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        callbackThread = new Thread(this::callbackLoop, name + "-callback");
        callbackThread.setDaemon(true);
        callbackThread.start();
    }

    public void callbackLoop() {
        while (!closed) {
            try {
                final java.nio.file.WatchKey key = watchKeys.take();
                final Path dirPath = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind().type() == Path.class) {
                        runCallbacks(dirPath, event);
                    } else if (event.kind().equals(OVERFLOW)) {
                        runCallbacks(dirPath, event);
                    } else {
                        logger.warn("Ignoring unexpected event type: {}", event.kind().name());
                    }
                }
                final boolean reset = key.reset();
                if (!reset) {
                    logger.debug("Path will no longer be watched: {}", dirPath);
                }
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Unexpected exception: ", e);
            }
        }
    }

    public void watchServiceLoop() {
        while (!closed) {
            try {
                final java.nio.file.WatchKey key = watchService.take();
                watchKeys.add(key);
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Unexpected exception: ", e);
            }
        }
    }

    void register(Path path, WatchKey key) {
        lock.lock();
        try {
            // normalize
            final java.nio.file.WatchKey watchKey;
            try {
                watchKey = path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to watch a dir " + path, e);
            }
            currWatchEventMap.computeIfAbsent(path, ignored -> new WatchCallbacks(watchKey, path))
                             .register(key);
            watchKeys.add(new InitialWatchKey(path));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Unregisters a {@link WatchKey}. The {@code callback} won't be invoked anymore when the
     * contents of the file for the {@code filePath} is changed. If no paths are watched by the
     * {@link WatchService}, then the background thread is stopped.
     *
     * @param watchRegisterKey the key for which the {@link WatchService} will stop watching.
     */
    void unregister(WatchKey watchRegisterKey) {
        lock.lock();
        try {
            final Path path = watchRegisterKey.path();
            final WatchCallbacks entry = currWatchEventMap.get(path);
            if (entry == null) {
                return;
            }
            entry.unregisterCallback(watchRegisterKey);
            if (!entry.hasCallback()) {
                currWatchEventMap.remove(path);
                entry.cancel();
            }
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    boolean isRunning() {
        return !closed;
    }

    boolean hasWatchers() {
        lock.lock();
        try {
            return !currWatchEventMap.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private void runCallbacks(Path dirPath, WatchEvent<?> event) {
        final Map<WatchKey, DirectoryWatcher> copiedCallbacks;
        lock.lock();
        try {
            final WatchCallbacks watchCallbacks = currWatchEventMap.get(dirPath);
            if (watchCallbacks == null) {
                return;
            }
            copiedCallbacks = ImmutableMap.copyOf(watchCallbacks.callbacks);
        } finally {
            lock.unlock();
        }
        for (DirectoryWatcher callback : copiedCallbacks.values()) {
            runSafely(dirPath, event, callback);
        }
    }

    private static void runSafely(Path dirPath, WatchEvent<?> event, DirectoryWatcher callback) {
        try {
            callback.onEvent(dirPath, event);
        } catch (Exception e) {
            logger.warn("Unexpected error from listener for: {} ", dirPath, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        watchThread.interrupt();
        callbackThread.interrupt();
        try {
            watchService.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lock.lock();
        try {
            currWatchEventMap.clear();
        } finally {
            lock.unlock();
        }
    }

    static class WatchCallbacks {
        private final java.nio.file.WatchKey watchKey;
        private final Map<WatchKey, DirectoryWatcher> callbacks;
        private final Path dirPath;

        WatchCallbacks(java.nio.file.WatchKey watchKey, Path dirPath) {
            this.watchKey = watchKey;
            this.dirPath = dirPath;
            callbacks = new HashMap<>();
        }

        void register(WatchKey key) {
            callbacks.put(key, key.callback());
        }

        void unregisterCallback(WatchKey key) {
            callbacks.remove(key);
        }

        boolean hasCallback() {
            return !callbacks.isEmpty();
        }

        void cancel() {
            watchKey.cancel();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("dirPath", dirPath)
                              .add("watchable", watchKey.watchable())
                              .add("isValid", watchKey.isValid())
                              .toString();
        }
    }

    private static final class InitialWatchEvent implements WatchEvent<Path> {

        private final Path dirPath;

        InitialWatchEvent(Path dirPath) {
            this.dirPath = dirPath;
        }

        @Override
        public Kind<Path> kind() {
            return WATCHER_REGISTERED;
        }

        @Override
        public int count() {
            return 0;
        }

        @Override
        public Path context() {
            return dirPath;
        }
    }

    private static final class InitialWatchKey implements java.nio.file.WatchKey {

        private final InitialWatchEvent initialWatchEvent;

        InitialWatchKey(Path dirPath) {
            initialWatchEvent = new InitialWatchEvent(dirPath);
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return ImmutableList.of(initialWatchEvent);
        }

        @Override
        public boolean reset() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public Watchable watchable() {
            return initialWatchEvent.dirPath;
        }
    }
}
