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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.Cancelable;
import com.linecorp.armeria.internal.testing.TemporaryFolderExtension;

class DirectoryWatchServiceTest {

    @RegisterExtension
    static TemporaryFolderExtension folder = new TemporaryFolderExtension();

    @BeforeAll
    static void before() {
        Awaitility.setDefaultTimeout(1, TimeUnit.MINUTES);
    }

    @AfterAll
    static void after() {
        Awaitility.setDefaultTimeout(10, TimeUnit.SECONDS);
    }

    @Test
    void emptyGroupStopsBackgroundThread() throws Exception {

        final Path file = folder.newFile("temp-file.properties");
        final Path file2 = folder.newFile("temp-file2.properties");

        try (DirectoryWatchService watchService = new DirectoryWatchService()) {
            final Cancelable key1 = watchService.register(file.getParent(), (path, event) -> {});
            final Cancelable key2 = watchService.register(file2.getParent(), (path, event) -> {});

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
        watchService.register(file.getParent(), (path, event) -> {});

        assertThat(watchService.hasWatchers()).isTrue();

        watchService.close();

        assertThat(watchService.hasWatchers()).isFalse();
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_17) // NIO.2 DirectoryWatchService doesn't work reliably on older Java.
    void runnableWithExceptionContinuesRun() throws Exception {

        final Path file = folder.newFile("temp-file.properties");
        final DirectoryWatchService watchService = new DirectoryWatchService();

        final AtomicInteger val = new AtomicInteger(0);
        final Cancelable key = watchService.register(file.getParent(), (path, event) -> {
            try {
                final BufferedReader bufferedReader = new BufferedReader(new FileReader(file.toFile()));
                val.set(Integer.valueOf(bufferedReader.readLine()));
            } catch (IOException e) {
                // do nothing
            }
            throw new RuntimeException();
        });

        PrintWriter printWriter = new PrintWriter(file.toFile());
        printWriter.print(1);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(1));

        assertThat(watchService.hasWatchers()).isTrue();

        printWriter = new PrintWriter(file.toFile());
        printWriter.print(2);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(2));

        assertThat(watchService.hasWatchers()).isTrue();

        key.cancel();

        assertThat(watchService.hasWatchers()).isFalse();

        watchService.close();
    }
}
