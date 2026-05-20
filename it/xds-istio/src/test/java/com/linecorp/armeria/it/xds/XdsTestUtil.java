/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.it.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test-only utilities for xDS integration tests.
 */
final class XdsTestUtil {

    /**
     * Waits for the specified bootstrap JSON file to appear under {@code /etc/istio/proxy/}
     * and returns its contents. Istio sidecars write these files asynchronously, so tests
     * must not assume they exist immediately.
     */
    static String awaitAndReadBootstrapJson(String filename) throws Exception {
        final Path path = Paths.get("/etc/istio/proxy/" + filename);
        await().untilAsserted(() -> assertThat(path).exists());
        return Files.readString(path);
    }

    private XdsTestUtil() {}
}
