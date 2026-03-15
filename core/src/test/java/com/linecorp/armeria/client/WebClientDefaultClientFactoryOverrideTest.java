/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ShutdownHookTestClientFactory;

final class WebClientDefaultClientFactoryOverrideTest {

    @Test
    void webClientOfShouldUseCustomDefaultClientFactory() throws IOException, InterruptedException {
        final String javaBin =
                System.getProperty("java.home") + System.getProperty("file.separator") + "bin" +
                System.getProperty("file.separator") + "java";
        final String classpath = System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                WebClientDefaultClientFactoryOverrideTestApp.class.getName())
                .redirectErrorStream(true)
                .start();

        final String output = readOutput(process.getInputStream());
        final int exitCode = process.waitFor();

        assertThat(exitCode).withFailMessage(output).isZero();
        assertThat(output).contains(
                ShutdownHookTestClientFactory.NEW_CLIENT_MARKER_PREFIX + WebClient.class.getName());
    }

    private static String readOutput(InputStream inputStream) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), UTF_8);
    }
}
