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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.MountableFile;

final class IstioTestImage {

    private static final Logger logger = LoggerFactory.getLogger(IstioTestImage.class);

    static final String IMAGE_NAME = "armeria-istio-test:latest";

    static ImageFromDockerfile build() {
        final Path runtimeDir = IstioEnv.testRuntimeDir();
        return new ImageFromDockerfile(IMAGE_NAME, false)
                .withDockerfileFromBuilder(builder -> builder
                        .from("eclipse-temurin:21-jre")
                        .copy("app/", "/app/")
                        .entryPoint("java", "-cp", "/app:/app/*",
                                    "com.linecorp.armeria.it.istio.testing.IstioPodEntryPoint")
                        .build())
                .withFileFromPath("app/", runtimeDir);
    }

    /**
     * Loads {@link #IMAGE_NAME} into the containerd store of a K3s container so that pods
     * scheduled with {@code imagePullPolicy: Never} can find it.
     *
     * <p>K3s runs its own containerd instance inside a Docker container, completely isolated
     * from the host Docker daemon where {@link #build()} places the image. The only way to
     * bridge the two is to serialize the image out of Docker ({@code docker save}), copy the
     * tar into the K3s container, and import it into containerd ({@code ctr images import}).
     */
    static void loadIntoK3s(K3sContainer k3sContainer) throws Exception {
        logger.info("Loading {} into K3s cluster...", IMAGE_NAME);
        build().get();

        final Path tempFile = Files.createTempFile(IstioEnv.dockerImagesDir(), "armeria-istio-test-", ".tar");
        try {
            final Process saveProcess = new ProcessBuilder("docker", "save", IMAGE_NAME)
                    .redirectOutput(tempFile.toFile())
                    .start();
            if (saveProcess.waitFor() != 0) {
                throw new IllegalStateException("docker save exited with non-zero status");
            }

            // K3s's containerd cannot reach the host Docker socket, so we copy the tar in
            // and import it directly into the k8s.io containerd namespace.
            k3sContainer.copyFileToContainer(
                    MountableFile.forHostPath(tempFile.toAbsolutePath().toString()),
                    "/tmp/armeria-istio-test.tar");

            final ExecResult importResult =
                    k3sContainer.execInContainer(
                            "ctr",
                            "--address", "/run/k3s/containerd/containerd.sock",
                            "--namespace", "k8s.io",
                            "images", "import", "/tmp/armeria-istio-test.tar");
            if (importResult.getExitCode() != 0) {
                throw new IllegalStateException(
                        "k3s ctr images import failed: " + importResult.getStderr());
            }
            logger.info("Image {} loaded into K3s.", IMAGE_NAME);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private IstioTestImage() {}
}
