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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;

final class FileWatcher implements PathWatcher {

    private final Path filePath;
    private final Consumer<byte[]> handler;
    private final Executor executor;

    @Nullable
    private FileTime lastModifiedTime;

    FileWatcher(Path filePath, Consumer<byte[]> handler, Executor executor) {
        this.filePath = filePath;
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
            final BasicFileAttributes basicFileAttributes;
            try {
                basicFileAttributes = Files.readAttributes(filePath, BasicFileAttributes.class);
            } catch (IOException e) {
                return;
            }
            if (Objects.equals(basicFileAttributes.lastModifiedTime(), lastModifiedTime)) {
                return;
            }
            final byte[] fileContent;
            try {
                fileContent = Files.readAllBytes(filePath);
            } catch (IOException e) {
                return;
            }
            lastModifiedTime = basicFileAttributes.lastModifiedTime();
            handler.accept(fileContent);
        }
    }
}
