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

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.annotation.Nullable;

final class FileWatcher implements PathWatcher {

    private static final Logger logger = LoggerFactory.getLogger(FileWatcher.class);

    private final Path filePath;
    @Nullable
    private final Predicate<Path> readGuard;
    private final Consumer<byte[]> handler;
    private final Executor executor;

    @Nullable
    private FileTime lastModifiedTime;
    private boolean readRefusalLogged;

    FileWatcher(Path filePath, @Nullable Predicate<Path> readGuard,
                Consumer<byte[]> handler, Executor executor) {
        this.filePath = filePath;
        this.readGuard = readGuard;
        this.handler = handler;
        this.executor = executor;
    }

    @Override
    public void onEvent(Path dirPath, @Nullable Path filePath, WatchEvent<?> event) {
        executor.execute(() -> onEvent0(event));
    }

    private void onEvent0(WatchEvent<?> event) {
        if (event.kind() == ENTRY_CREATE ||
            event.kind() == ENTRY_MODIFY ||
            event.kind() == OVERFLOW ||
            event.kind() == WatcherRegisteredKind.of()) {
            final Path readPath = resolveReadPath();
            if (readPath == null) {
                return;
            }
            final BasicFileAttributes basicFileAttributes;
            try {
                basicFileAttributes = readAttributes(readPath);
            } catch (IOException e) {
                logger.trace("Not reading '{}': could not read attributes.", readPath, e);
                return;
            }
            if (Objects.equals(basicFileAttributes.lastModifiedTime(), lastModifiedTime)) {
                return;
            }
            final byte[] fileContent;
            try {
                fileContent = readAllBytes(readPath);
            } catch (IOException e) {
                logger.trace("Failed to read '{}'.", readPath, e);
                return;
            }
            lastModifiedTime = basicFileAttributes.lastModifiedTime();
            handler.accept(fileContent);
        }
    }

    /**
     * Resolves {@link #filePath} to the path to read: the file itself when there is no read guard, or
     * its guard-approved real path. Returns {@code null} to refuse the read.
     */
    @Nullable
    private Path resolveReadPath() {
        if (readGuard == null) {
            return filePath;
        }
        final Path realPath;
        try {
            realPath = filePath.toRealPath();
        } catch (IOException e) {
            // The file may not exist yet or could not be resolved; fail closed by not reading.
            logger.trace("Not reading '{}': could not resolve real path.", filePath, e);
            return null;
        }
        if (!readGuard.test(realPath)) {
            if (!readRefusalLogged) {
                readRefusalLogged = true;
                logger.warn("Refusing to read '{}' because its resolved real path '{}' is not " +
                            "permitted by the read guard (possible symbolic link escape).",
                            filePath, realPath);
            }
            return null;
        }
        return realPath;
    }

    private BasicFileAttributes readAttributes(Path path) throws IOException {
        if (readGuard != null) {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } else {
            return Files.readAttributes(path, BasicFileAttributes.class);
        }
    }

    private byte[] readAllBytes(Path path) throws IOException {
        if (readGuard != null) {
            // Open with NOFOLLOW_LINKS so a symlink swapped onto the final component after the guard
            // check fails closed instead of being followed outside what the guard allows.
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                return ByteStreams.toByteArray(in);
            }
        } else {
            return Files.readAllBytes(path);
        }
    }
}
