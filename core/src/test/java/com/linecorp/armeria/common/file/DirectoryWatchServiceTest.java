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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.Cancellable;
import com.linecorp.armeria.internal.testing.TemporaryFolderExtension;

class DirectoryWatchServiceTest {

    @RegisterExtension
    static TemporaryFolderExtension folder = new TemporaryFolderExtension();

    @Test
    void emptyGroupStopsBackgroundThread() throws Exception {

        final Path file = folder.newFile("temp-file.properties");
        final Path file2 = folder.newFile("temp-file2.properties");

        try (DirectoryWatchService watchService = new DirectoryWatchService()) {
            final Cancellable key1 = watchService.register(file.getParent(), (path, filePath, event) -> {});
            final Cancellable key2 = watchService.register(file2.getParent(), (path, filePath, event) -> {});

            assertThat(watchService.hasWatchers()).isTrue();

            key1.cancel();

            assertThat(watchService.hasWatchers()).isTrue();

            key2.cancel();

            assertThat(watchService.hasWatchers()).isFalse();
        }
    }

    @Test
    void closeEndpointGroupStopsRegistry() throws Exception {

        final Path file = folder.newFile("temp-file.properties");

        final DirectoryWatchService watchService = new DirectoryWatchService();
        watchService.register(file.getParent(), (path, filePath, event) -> {});

        assertThat(watchService.hasWatchers()).isTrue();

        watchService.close();

        assertThat(watchService.hasWatchers()).isFalse();
    }

    @Test
    void filePathVerification() throws Exception {

        final Path file = folder.newFile("temp-file1.properties");
        final DirectoryWatchService watchService = new DirectoryWatchService();
        final AtomicReference<Path> filePathRef = new AtomicReference<>();

        final Cancellable key = watchService.register(file.getParent(), (path, filePath, event) -> {
            if (filePath != null) {
                filePathRef.set(filePath);
            }
        });

        try (PrintWriter printWriter = new PrintWriter(file.toFile())) {
            printWriter.print(1);
            printWriter.flush();
        }
        await().untilAsserted(() -> assertThat(filePathRef.get()).isEqualTo(file));
        key.cancel();
        watchService.close();
    }

    @Test
    void runnableWithExceptionContinuesRun() throws Exception {

        final Path file = folder.newFile("temp-file.properties");
        final DirectoryWatchService watchService = new DirectoryWatchService();

        final AtomicInteger val = new AtomicInteger(0);
        final Cancellable key = watchService.register(file.getParent(), (path, filePath, event) -> {
            try (BufferedReader bufferedReader = new BufferedReader(new FileReader(file.toFile()))) {
                val.set(Integer.valueOf(bufferedReader.readLine()));
            } catch (IOException e) {
                // do nothing
            }
            throw new RuntimeException();
        });

        try (PrintWriter printWriter = new PrintWriter(file.toFile())) {
            printWriter.print(1);
        }

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(1));

        assertThat(watchService.hasWatchers()).isTrue();

        try (PrintWriter printWriter = new PrintWriter(file.toFile())) {
            printWriter.print(2);
        }

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(2));

        assertThat(watchService.hasWatchers()).isTrue();

        key.cancel();

        assertThat(watchService.hasWatchers()).isFalse();

        watchService.close();
    }
}
