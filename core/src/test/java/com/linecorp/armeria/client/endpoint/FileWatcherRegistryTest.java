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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.FileWatcherRegistry.FileWatchRegisterKey;
import com.linecorp.armeria.internal.testing.TemporaryFolderExtension;

class FileWatcherRegistryTest {

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

    // Not sure if there is a better way to test multi-filesystem other than mocking.
    private static Path createMockedPath() throws Exception {
        final Path path = mock(Path.class);
        final FileSystem fileSystem = mock(FileSystem.class);
        final WatchService watchService = mock(WatchService.class);
        final WatchKey watchKey = mock(WatchKey.class);
        lenient().when(path.toRealPath()).thenReturn(path);
        when(path.getParent()).thenReturn(path);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.newWatchService()).thenReturn(watchService);
        when(path.register(any(), any())).thenReturn(watchKey);
        when(watchService.take()).then(invocation -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            return watchKey;
        });
        lenient().when(watchKey.reset()).thenReturn(true);
        return path;
    }

    @AfterEach
    void tearDown() throws Exception {
        PropertiesEndpointGroup.resetRegistry();
    }

    @Test
    void emptyGroupStopsBackgroundThread() throws Exception {

        final Path file = folder.newFile("temp-file.properties");
        final Path file2 = folder.newFile("temp-file2.properties");

        try (FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry()) {
            final FileWatchRegisterKey key1 = fileWatcherRegistry.register(file, () -> {});
            final FileWatchRegisterKey key2 = fileWatcherRegistry.register(file2, () -> {});

            assertThat(fileWatcherRegistry.isRunning()).isTrue();

            fileWatcherRegistry.unregister(key1);

            assertThat(fileWatcherRegistry.isRunning()).isTrue();

            fileWatcherRegistry.unregister(key2);

            assertThat(fileWatcherRegistry.isRunning()).isFalse();
        }
    }

    @Test
    void closeEndpointGroupStopsRegistry() throws Exception {

        final Path file = folder.newFile("temp-file.properties");

        final FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry();
        fileWatcherRegistry.register(file, () -> {});

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.close();

        assertThat(fileWatcherRegistry.isRunning()).isFalse();
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_17) // NIO.2 WatchService doesn't work reliably on older Java.
    void runnableWithExceptionContinuesRun() throws Exception {

        final Path file = folder.newFile("temp-file.properties");
        final FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry();

        final AtomicInteger val = new AtomicInteger(0);
        final FileWatchRegisterKey key = fileWatcherRegistry.register(file, () -> {
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

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        printWriter = new PrintWriter(file.toFile());
        printWriter.print(2);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(2));

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.unregister(key);

        assertThat(fileWatcherRegistry.isRunning()).isFalse();

        fileWatcherRegistry.close();
    }

    @Test
    void testMultipleFileSystems() throws Exception {
        try (FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry()) {
            final Path path1 = createMockedPath();
            final Path path2 = createMockedPath();

            final FileWatchRegisterKey key1 = fileWatcherRegistry.register(path1, () -> {
            });
            final FileWatchRegisterKey key2 = fileWatcherRegistry.register(path2, () -> {
            });
            assertThat(fileWatcherRegistry.isRunning()).isTrue();

            fileWatcherRegistry.unregister(key1);
            assertThat(fileWatcherRegistry.isRunning()).isTrue();

            fileWatcherRegistry.unregister(key2);
            assertThat(fileWatcherRegistry.isRunning()).isFalse();
        }
    }
}
