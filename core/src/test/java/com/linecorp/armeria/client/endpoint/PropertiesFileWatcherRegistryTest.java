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

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PropertiesFileWatcherRegistryTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void stopFutureCorrectly() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final File file2 = folder.newFile("temp-file2.properties");

        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry();
        propertiesFileWatcherRegistry.register(file.toURI().toURL(), () -> {});
        propertiesFileWatcherRegistry.register(file2.toURI().toURL(), () -> {});

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.deregister(file.toURI().toURL());

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.deregister(file2.toURI().toURL());

        assertThat(propertiesFileWatcherRegistry.isRunning()).isFalse();
    }
}
