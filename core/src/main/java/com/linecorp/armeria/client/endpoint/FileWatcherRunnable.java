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
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collection;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes a runnable which watches files.
 */
class FileWatcherRunnable implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(FileWatcherRunnable.class);

    private final WatchService watchService;
    private final Supplier<Collection<FileWatcherContext>> ctxSupplier;

    /**
     * Initializes a runnable which watches files.
     * @param watchService the {@code WatchService} to poll events from
     * @param ctxSupplier supplier which contains the callback invoked for each event
     */
    FileWatcherRunnable(WatchService watchService, Supplier<Collection<FileWatcherContext>> ctxSupplier) {
        this.watchService = watchService;
        this.ctxSupplier = ctxSupplier;
    }

    @Override
    public void run() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    @SuppressWarnings("unchecked")
                    final Path watchedPath = ((Path) key.watchable())
                            .resolve(((WatchEvent<Path>) event).context());
                    final Path realFilePath;
                    try {
                        realFilePath = watchedPath.toRealPath();
                    } catch (IOException e) {
                        logger.warn("skipping -- unable to get real path for {}", watchedPath);
                        continue;
                    }
                    if (event.kind().equals(ENTRY_MODIFY) || event.kind().equals(ENTRY_CREATE)) {
                        runCallback(realFilePath);
                    } else if (event.kind().equals(OVERFLOW)) {
                        logger.debug("watch event may have been lost: {}", realFilePath);
                        runCallback(realFilePath);
                    } else if (event.kind().equals(ENTRY_DELETE)) {
                        logger.warn("ignoring deleted file: {}", realFilePath);
                    }
                }

                final boolean reset = key.reset();
                if (!reset) {
                    logger.warn("aborting file watch due to unexpected error");
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

    private void runCallback(Path filePath) {
        ctxSupplier.get().stream().filter(
                ctx -> filePath.startsWith(ctx.path())).forEach(ctx -> {
            try {
                ctx.runCallback();
            } catch (Exception e) {
                logger.warn("unexpected error from listener: {} ", filePath, e);
            }
        });
    }

    /**
     * Utility class which contains context info for each watched path.
     */
    static class FileWatcherContext {
        private final WatchKey key;
        private final Runnable callback;
        private final Path dirPath;

        /**
         * Initializes the context for each watched path.
         * @param key the {@code WatchKey} registered for the current path
         * @param callback callback to be invoked on a watch event
         * @param dirPath path which is watched
         */
        FileWatcherContext(WatchKey key, Runnable callback, Path dirPath) {
            this.key = key;
            this.callback = callback;
            this.dirPath = dirPath;
        }

        private void runCallback() {
            callback.run();
        }

        public Path path() {
            return dirPath;
        }

        public void cancel() {
            key.cancel();
        }
    }
}
