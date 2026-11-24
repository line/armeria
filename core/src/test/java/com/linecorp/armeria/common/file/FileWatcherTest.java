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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.common.annotation.Nullable;

class FileWatcherTest {

    /**
     * A synthetic {@link WatchEvent} of {@link WatcherRegisteredKind}, which {@link FileWatcher} treats
     * like a create/modify event and reads the file in response.
     */
    private static WatchEvent<?> registeredEvent() {
        return new WatchEvent<Object>() {
            @SuppressWarnings("unchecked")
            @Override
            public Kind<Object> kind() {
                return (Kind<Object>) (Kind<?>) WatcherRegisteredKind.of();
            }

            @Override
            public int count() {
                return 1;
            }

            @Nullable
            @Override
            public Object context() {
                return null;
            }
        };
    }

    private static AtomicReference<byte[]> fire(Path filePath, @Nullable Predicate<Path> readGuard) {
        final AtomicReference<byte[]> received = new AtomicReference<>();
        // Runnable::run makes onEvent read synchronously on the calling thread.
        final FileWatcher watcher = new FileWatcher(filePath, readGuard, received::set, Runnable::run);
        watcher.onEvent(filePath.getParent(), filePath, registeredEvent());
        return received;
    }

    @Test
    void confinedToGuardBlocksSymlinkEscape(@TempDir Path allowedDir,
                                            @TempDir Path outsideDir) throws Exception {
        final Path secret = Files.write(outsideDir.resolve("secret"),
                                        "top-secret".getBytes(StandardCharsets.UTF_8));
        final Path link = Files.createSymbolicLink(allowedDir.resolve("link"), secret);
        // The PathWatcher.confinedTo factory, used as the read guard, blocks the escaping symlink.
        assertThat(fire(link, PathWatcher.confinedTo(allowedDir)).get()).isNull();
    }

    @Test
    void symlinkPlantedAfterCheckIsNotFollowed(@TempDir Path allowedDir,
                                               @TempDir Path outsideDir) throws Exception {
        final Path target = Files.write(allowedDir.resolve("target"),
                                        "inside".getBytes(StandardCharsets.UTF_8));
        final Path secret = Files.write(outsideDir.resolve("secret"),
                                        "top-secret".getBytes(StandardCharsets.UTF_8));
        // A guard that passes but, as a side effect, swaps the just-checked regular file for a symlink
        // to the outside secret, simulating a time-of-check/time-of-use race. The read runs after the
        // guard, so it must not follow the planted symlink and leak the outside secret.
        final Predicate<Path> guard = realPath -> {
            try {
                Files.delete(target);
                Files.createSymbolicLink(target, secret);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return true;
        };
        assertThat(fire(target, guard).get()).isNull();
    }

    @Test
    void confinedToGuardAllowsSymlinkWithinRoot(@TempDir Path allowedDir) throws Exception {
        final Path dataDir = Files.createDirectory(allowedDir.resolve("..data-like"));
        final Path target = Files.write(dataDir.resolve("cert"), "cert".getBytes(StandardCharsets.UTF_8));
        final Path link = Files.createSymbolicLink(allowedDir.resolve("cert"), target);
        assertThat(fire(link, PathWatcher.confinedTo(allowedDir)))
                .hasValue("cert".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void noGuardReadsFileContent(@TempDir Path dir) throws Exception {
        final Path file = Files.write(dir.resolve("f"), "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(fire(file, null)).hasValue("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void guardReceivesResolvedRealPathAndReadsIt(@TempDir Path dir) throws Exception {
        final Path target = Files.write(dir.resolve("target"), "secret".getBytes(StandardCharsets.UTF_8));
        final Path link = Files.createSymbolicLink(dir.resolve("link"), target);

        final AtomicReference<Path> guardArg = new AtomicReference<>();
        final Predicate<Path> guard = path -> {
            guardArg.set(path);
            return true;
        };
        // The guard is invoked with the symlink-resolved real path, and the resolved path is read.
        assertThat(fire(link, guard)).hasValue("secret".getBytes(StandardCharsets.UTF_8));
        assertThat(guardArg.get()).isEqualTo(target.toRealPath());
    }

    @Test
    void guardRejectionSkipsRead(@TempDir Path dir) throws Exception {
        final Path file = Files.write(dir.resolve("f"), "hello".getBytes(StandardCharsets.UTF_8));
        // A guard that always rejects must prevent the read entirely.
        assertThat(fire(file, path -> false).get()).isNull();
    }

    @Test
    void symlinkEscapingAllowedRootIsBlocked(@TempDir Path allowedDir,
                                             @TempDir Path outsideDir) throws Exception {
        final Path secret = Files.write(outsideDir.resolve("secret"),
                                        "top-secret".getBytes(StandardCharsets.UTF_8));
        // A symlink placed *inside* the allowed root, pointing *outside* it.
        final Path link = Files.createSymbolicLink(allowedDir.resolve("link"), secret);

        final Path realAllowedRoot = allowedDir.toRealPath();
        final Predicate<Path> confinedToAllowedRoot = path -> path.startsWith(realAllowedRoot);

        // Reading the symlink would follow it to the outside secret; the guard resolves and blocks it.
        assertThat(fire(link, confinedToAllowedRoot).get()).isNull();
    }

    @Test
    void symlinkWithinAllowedRootIsPermitted(@TempDir Path allowedDir) throws Exception {
        // Mirrors Kubernetes secret mounts: a symlink under the root pointing to another file under it.
        final Path dataDir = Files.createDirectory(allowedDir.resolve("..data-like"));
        final Path target = Files.write(dataDir.resolve("cert"), "cert".getBytes(StandardCharsets.UTF_8));
        final Path link = Files.createSymbolicLink(allowedDir.resolve("cert"), target);

        final Path realAllowedRoot = allowedDir.toRealPath();
        final Predicate<Path> confinedToAllowedRoot = path -> path.startsWith(realAllowedRoot);

        assertThat(fire(link, confinedToAllowedRoot)).hasValue("cert".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void guardIsReevaluatedOnEachEvent(@TempDir Path allowedDir,
                                       @TempDir Path outsideDir) throws Exception {
        final Path inside = Files.write(allowedDir.resolve("inside"),
                                        "inside".getBytes(StandardCharsets.UTF_8));
        final Path outside = Files.write(outsideDir.resolve("outside"),
                                         "outside".getBytes(StandardCharsets.UTF_8));
        // A stable symlink path that initially points inside the allowed root.
        final Path link = Files.createSymbolicLink(allowedDir.resolve("link"), inside);

        final Path realAllowedRoot = allowedDir.toRealPath();
        final AtomicReference<byte[]> received = new AtomicReference<>();
        final FileWatcher watcher = new FileWatcher(
                link, path -> path.startsWith(realAllowedRoot), received::set, Runnable::run);

        // First event: the link resolves inside the root, so the content is read.
        watcher.onEvent(allowedDir, link, registeredEvent());
        assertThat(received.get()).isEqualTo("inside".getBytes(StandardCharsets.UTF_8));

        // The link is swapped to point outside the root (time-of-check/time-of-use attack).
        Files.delete(link);
        Files.createSymbolicLink(link, outside);

        // Second event: the guard is re-evaluated against the new real path and blocks the read, so the
        // previously delivered content is not overwritten with the outside secret.
        watcher.onEvent(allowedDir, link, registeredEvent());
        assertThat(received.get()).isEqualTo("inside".getBytes(StandardCharsets.UTF_8));
    }
}
