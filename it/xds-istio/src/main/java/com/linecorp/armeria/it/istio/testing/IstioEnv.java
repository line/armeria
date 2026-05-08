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
package com.linecorp.armeria.it.istio.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class IstioEnv {

    private static final String KUBECONFIG_PATH_ENV        = "KUBECONFIG_PATH";
    private static final String ISTIO_VERSION_ENV          = "ISTIO_VERSION";
    private static final String ISTIO_PROFILE_ENV          = "ISTIO_PROFILE";
    private static final String ISTIOCTL_PATH_ENV          = "ISTIOCTL_PATH";
    private static final String ISTIO_TEST_RUNTIME_DIR_ENV    = "ISTIO_TEST_RUNTIME_DIR";
    private static final String ISTIO_DOCKER_IMAGES_DIR_ENV   = "ISTIO_DOCKER_IMAGES_DIR";
    private static final String ISTIO_POD_JVM_ARGS_ENV        = "ISTIO_POD_JVM_ARGS";

    private IstioEnv() {}

    static Path kubeconfigPath() {
        return Paths.get(require(KUBECONFIG_PATH_ENV));
    }

    static String istioVersion() {
        return require(ISTIO_VERSION_ENV);
    }

    static String istioProfile() {
        return require(ISTIO_PROFILE_ENV);
    }

    static Path testRuntimeDir() {
        return Paths.get(require(ISTIO_TEST_RUNTIME_DIR_ENV));
    }

    static Path dockerImagesDir() {
        return Paths.get(require(ISTIO_DOCKER_IMAGES_DIR_ENV));
    }

    static Path istioctlPath() {
        final Path path = Paths.get(require(ISTIOCTL_PATH_ENV));
        if (!Files.isExecutable(path)) {
            throw new IllegalStateException("istioctl is not executable: " + path);
        }
        return path;
    }

    static String podJvmArgs() {
        return require(ISTIO_POD_JVM_ARGS_ENV);
    }

    private static String require(String name) {
        final String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " must be set.");
        }
        return v.trim();
    }
}
