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

import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.endpoint.FileWatcherRegistry.FileSystemWatchContext;

/**
 * A runnable which watches files.
 */
final class FileWatcherRunnable implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(FileWatcherRunnable.class);

    private final WatchService watchService;
    private final FileSystemWatchContext fileSystemWatchContext;

    /**
     * Initializes a runnable which watches files.
     * @param watchService the {@code WatchService} to receive events from
     * @param fileSystemWatchContext the context which contains target files
     */
    FileWatcherRunnable(WatchService watchService, FileSystemWatchContext fileSystemWatchContext) {
        this.watchService = watchService;
        this.fileSystemWatchContext = fileSystemWatchContext;
    }

    @Override
    public void run() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                final Path dirPath = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind().type() == Path.class) {
                        @SuppressWarnings("unchecked")
                        final Path filePath = dirPath.resolve(((WatchEvent<Path>) event).context());
                        if (event.kind().equals(ENTRY_DELETE)) {
                            logger.warn("Ignoring a deleted file: {}", filePath);
                            continue;
                        }
                        runCallback(filePath);
                    } else if (event.kind().equals(OVERFLOW)) {
                        logger.debug("Watch events may have been lost for path: {}", dirPath);
                        runCallback(dirPath);
                    } else {
                        logger.debug("Ignoring unexpected event type: {}", event.kind().name());
                    }
                }
                final boolean reset = key.reset();
                if (!reset) {
                    logger.info("Path will no longer be watched: {}", dirPath);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.trace("File watching thread interrupted");
        } catch (ClosedWatchServiceException e) {
            // do nothing
        }
    }

    private void runCallback(Path path) {
        fileSystemWatchContext.watchEvents().stream().filter(
                ctx -> path.startsWith(ctx.dirPath())).forEach(ctx -> {
            try {
                ctx.runCallback();
            } catch (Exception e) {
                logger.warn("Unexpected error from listener: {} ", path, e);
            }
        });
    }

    /**
     * Utility class which contains context info for each watched path.
     */
    static class FileWatchEvent {
        private final WatchKey watchKey;
        private final Runnable callback;
        private final Path dirPath;

        /**
         * Initializes the context for each watched path.
         * @param watchKey the {@link WatchKey} registered for the current path
         * @param callback the callback which is invoked on a watch event
         * @param dirPath path which is watched
         */
        FileWatchEvent(WatchKey watchKey, Runnable callback, Path dirPath) {
            this.watchKey = watchKey;
            this.callback = callback;
            this.dirPath = dirPath;
        }

        private void runCallback() {
            callback.run();
        }

        Path dirPath() {
            return dirPath;
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
}
