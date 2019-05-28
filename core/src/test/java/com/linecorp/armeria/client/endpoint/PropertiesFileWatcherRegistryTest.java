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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PropertiesFileWatcherRegistryTest {

    @BeforeClass
    public static void before() {
        Awaitility.setDefaultTimeout(1, TimeUnit.MINUTES);
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void stopFutureCorrectly() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final PropertiesEndpointGroup group1 = PropertiesEndpointGroup.of(file.toPath(), "");
        final File file2 = folder.newFile("temp-file2.properties");
        final PropertiesEndpointGroup group2 = PropertiesEndpointGroup.of(file.toPath(), "");

        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry();
        propertiesFileWatcherRegistry.register(group1, file.toPath(), () -> {});
        propertiesFileWatcherRegistry.register(group2, file2.toPath(), () -> {});

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.deregister(group1);

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.deregister(group2);

        assertThat(propertiesFileWatcherRegistry.isRunning()).isFalse();
    }

    @Test
    public void closeStopsRegistry() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final PropertiesEndpointGroup group = PropertiesEndpointGroup.of(file.toPath(), "");

        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry();
        propertiesFileWatcherRegistry.register(group, file.toPath(), () -> {});

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.close();

        assertThat(propertiesFileWatcherRegistry.isRunning()).isFalse();
    }

    @Test
    public void runnableWithException() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry();
        final PropertiesEndpointGroup group = PropertiesEndpointGroup.of(file.toPath(), "");

        final AtomicInteger val = new AtomicInteger(0);
        propertiesFileWatcherRegistry.register(group, file.toPath(), () -> {
            try {
                final BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
                val.set(Integer.valueOf(bufferedReader.readLine()));
            } catch (IOException e) {
                // do nothing
            }
            throw new RuntimeException();
        });

        PrintWriter printWriter = new PrintWriter(file);
        printWriter.print(1);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(1));

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        printWriter = new PrintWriter(file);
        printWriter.print(2);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(2));

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();
    }
}
