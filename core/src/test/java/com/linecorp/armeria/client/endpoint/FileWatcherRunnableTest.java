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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.WatchService;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.endpoint.FileWatcherRegistry.FileSystemWatchContext;

class FileWatcherRunnableTest {

    @Test
    void testPropertyFileWatcherRunnableExitsOnInterrupt() throws InterruptedException {
        final WatchService watchService = mock(WatchService.class);
        final FileWatcherRunnable fileWatcherRunnable = new FileWatcherRunnable(watchService, mock(
                FileSystemWatchContext.class));
        when(watchService.take()).then(invocation -> {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.yield();
            }
            return null;
        });
        final Thread thread = new Thread(fileWatcherRunnable);
        thread.start();
        thread.interrupt();
        await().untilAsserted(() -> assertThat(thread.isAlive()).isFalse());
    }
}
