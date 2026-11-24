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

package com.linecorp.armeria.common.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PathWatcher#confinedTo(Path)}. The guard is exercised directly with a resolved
 * real path (the value {@code FileWatcher} passes it), so these do not need a live watch service.
 */
class PathWatcherTest {

    private static Path writeFile(Path path) throws Exception {
        return Files.write(path, "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void permitsPathUnderRoot(@TempDir Path root) throws Exception {
        final Path file = writeFile(root.resolve("f"));
        assertThat(PathWatcher.confinedTo(root).test(file.toRealPath())).isTrue();
    }

    @Test
    void permitsSymlinkTargetUnderRoot(@TempDir Path root) throws Exception {
        // Mirrors a Kubernetes secret mount: the real target sits in a subdirectory of the root.
        final Path dataDir = Files.createDirectory(root.resolve("..data-like"));
        final Path target = writeFile(dataDir.resolve("cert"));
        assertThat(PathWatcher.confinedTo(root).test(target.toRealPath())).isTrue();
    }

    @Test
    void blocksPathOutsideRoot(@TempDir Path root, @TempDir Path outside) throws Exception {
        final Path outsideFile = writeFile(outside.resolve("secret"));
        assertThat(PathWatcher.confinedTo(root).test(outsideFile.toRealPath())).isFalse();
    }

    @Test
    void failsClosedWhenRootMissing(@TempDir Path base) throws Exception {
        final Path missingRoot = base.resolve("does-not-exist");
        final Path file = writeFile(base.resolve("f"));
        assertThat(PathWatcher.confinedTo(missingRoot).test(file.toRealPath())).isFalse();
    }

    @Test
    void resolvesSymlinkedRoot(@TempDir Path base) throws Exception {
        final Path realRoot = Files.createDirectory(base.resolve("real"));
        final Path linkRoot = Files.createSymbolicLink(base.resolve("link"), realRoot);
        final Path file = writeFile(realRoot.resolve("f"));
        // The root itself is reached through a symlink; both sides must be resolved to match.
        assertThat(PathWatcher.confinedTo(linkRoot).test(file.toRealPath())).isTrue();
    }

    @Test
    void doesNotMatchSiblingPrefixDir(@TempDir Path base) throws Exception {
        // startsWith is component-wise: '/base/allowed-evil' must not be treated as under '/base/allowed'.
        final Path root = Files.createDirectory(base.resolve("allowed"));
        final Path sibling = Files.createDirectory(base.resolve("allowed-evil"));
        final Path file = writeFile(sibling.resolve("f"));
        assertThat(PathWatcher.confinedTo(root).test(file.toRealPath())).isFalse();
    }

    @Test
    void rejectsNullRoot() {
        assertThatThrownBy(() -> PathWatcher.confinedTo(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("root");
    }
}
