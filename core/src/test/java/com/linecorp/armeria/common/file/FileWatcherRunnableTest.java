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

import java.nio.file.FileSystems;

import org.junit.jupiter.api.Test;

class FileWatcherRunnableTest {

    @Test
    void testPropertyFileWatcherRunnableExitsOnInterrupt() throws Exception {
        final AsyncWatchService fileWatcherRunnable =
                new AsyncWatchService("my-service", FileSystems.getDefault());
        await().untilAsserted(() -> assertThat(fileWatcherRunnable.isRunning()).isTrue());
        fileWatcherRunnable.close();
        await().untilAsserted(() -> assertThat(fileWatcherRunnable.isRunning()).isFalse());
    }
}
