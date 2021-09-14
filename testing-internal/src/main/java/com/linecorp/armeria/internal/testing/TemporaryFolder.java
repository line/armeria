/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A helper class to handle temporary folders in JUnit {@code Extension}s.
 */
public final class TemporaryFolder {

    // Forked from CentralDogma 0.44.4
    // https://github.com/line/centraldogma/blob/4dbc351addc92b509f77be784605b88c3d1b21f2/testing/common/src/main/java/com/linecorp/centraldogma/testing/internal/TemporaryFolder.java
    @Nullable
    private Path root;

    public Path create(String prefix) throws IOException {
        return root = Files.createTempDirectory(prefix);
    }

    public Path create() throws IOException {
        return create("armeria");
    }

    public boolean exists() {
        return root != null;
    }

    public Path getRoot() {
        if (root == null) {
            throw new IllegalStateException("The temporary folder has not been created yet");
        }

        return root;
    }

    public Path newFolder() throws IOException {
        return Files.createTempDirectory(getRoot(), "");
    }

    public Path newFile() throws IOException {
        return Files.createTempFile(getRoot(), "", "");
    }

    public void delete() throws IOException {
        if (root == null) {
            return;
        }

        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }

        root = null;
    }
}
