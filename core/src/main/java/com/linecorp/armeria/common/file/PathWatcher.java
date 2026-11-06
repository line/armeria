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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A callback that is invoked when a file or directory watch event occurs.
 * Use this interface to respond to file system changes detected by a {@link DirectoryWatchService}.
 */
@UnstableApi
@FunctionalInterface
public interface PathWatcher {

    /**
     * Invoked when a watch event occurs on the registered directory or file.
     *
     * @param dirPath the directory path being watched
     * @param filePath the file path that triggered the watch if exists
     * @param event the {@link WatchEvent} that occurred
     */
    void onEvent(Path dirPath, @Nullable Path filePath, WatchEvent<?> event);

    /**
     * Creates a caching {@link PathWatcher} that reads the file content when the watched directory
     * changes and invokes the callback with the file bytes. The callback caches the last modified time
     * to avoid redundant reads when the file hasn't changed. Note that the watched directory is not
     * necessarily the parent directory of the file path.
     *
     * <p>Symbolic links are followed unconditionally. To confine reads to a directory subtree and reject
     * links whose real target escapes it, use an overload that accepts a {@code readGuard} together with
     * {@link #confinedTo(Path)}.
     *
     * @param filePath the path to the file to read
     * @param callback the consumer to invoke with the file content as bytes
     * @param executor an executor used to read the file
     * @return a new caching {@link PathWatcher}
     */
    static PathWatcher ofFile(Path filePath, Consumer<byte[]> callback,
                              Executor executor) {
        return ofFile(filePath, null, callback, executor);
    }

    /**
     * Creates a caching {@link PathWatcher} that reads the file content when the watched directory
     * changes and invokes the callback with the file bytes. The callback caches the last modified time
     * to avoid redundant reads when the file hasn't changed. Note that the watched directory is not
     * necessarily the parent directory of the file path.
     *
     * <p>Symbolic links are followed unconditionally. To confine reads to a directory subtree and reject
     * links whose real target escapes it, use an overload that accepts a {@code readGuard} together with
     * {@link #confinedTo(Path)}.
     *
     * @param filePath the path to the file to read
     * @param callback the consumer to invoke with the file content as bytes
     * @return a new caching {@link PathWatcher}
     */
    static PathWatcher ofFile(Path filePath, Consumer<byte[]> callback) {
        return ofFile(filePath, null, callback, CommonPools.blockingTaskExecutor());
    }

    /**
     * Creates a caching {@link PathWatcher} that reads the file content when the watched directory
     * changes and invokes the callback with the file bytes. The callback caches the last modified time
     * to avoid redundant reads when the file hasn't changed. Note that the watched directory is not
     * necessarily the parent directory of the file path.
     *
     * <p>When {@code readGuard} is non-{@code null}, the file path is resolved with symbolic links
     * followed (via {@link Path#toRealPath}) and the resolved real path is passed to the guard
     * immediately before each read. If the guard returns {@code false}, the file is not read and the
     * callback is not invoked.
     *
     * @param filePath the path to the file to read
     * @param readGuard tested against the resolved real path before each read;
     *                  {@link #confinedTo(Path)} returns one that confines reads to a directory subtree
     * @param callback the consumer to invoke with the file content as bytes
     * @return a new caching {@link PathWatcher}
     */
    static PathWatcher ofFile(Path filePath, @Nullable Predicate<Path> readGuard,
                              Consumer<byte[]> callback) {
        return ofFile(filePath, readGuard, callback, CommonPools.blockingTaskExecutor());
    }

    /**
     * Creates a caching {@link PathWatcher} that reads the file content when the watched directory
     * changes and invokes the callback with the file bytes. The callback caches the last modified time
     * to avoid redundant reads when the file hasn't changed. Note that the watched directory is not
     * necessarily the parent directory of the file path.
     *
     * <p>When {@code readGuard} is non-{@code null}, the file path is resolved with symbolic links
     * followed (via {@link Path#toRealPath}) and the resolved real path is passed to the guard
     * immediately before each read. If the guard returns {@code false}, the file is not read and the
     * callback is not invoked.
     *
     * @param filePath the path to the file to read
     * @param readGuard tested against the resolved real path before each read;
     *                  {@link #confinedTo(Path)} returns one that confines reads to a directory subtree
     * @param callback the consumer to invoke with the file content as bytes
     * @param executor an executor used to read the file
     * @return a new caching {@link PathWatcher}
     */
    static PathWatcher ofFile(Path filePath, @Nullable Predicate<Path> readGuard,
                              Consumer<byte[]> callback, Executor executor) {
        requireNonNull(filePath, "filePath");
        requireNonNull(callback, "callback");
        requireNonNull(executor, "executor");
        return new FileWatcher(filePath, readGuard, callback, executor);
    }

    /**
     * Returns a read guard, suitable for {@link #ofFile(Path, Predicate, Consumer)}, that permits a read
     * only when the file's resolved real path is located under {@code root}. Both the candidate path
     * (already resolved by the watcher) and {@code root} are resolved with
     * {@link Path#toRealPath(LinkOption...)} before comparison, so a symbolic link is judged by where it
     * actually points.
     *
     * <p>{@code root} is resolved on each read, so a {@code root} that does not exist yet is treated as
     * containing nothing (the read is refused) until it appears. {@code root} should be absolute; a
     * relative path is resolved against the current working directory.
     *
     * <p>This confinement holds only if {@code root} and its ancestor directories are writable by
     * trusted principals. An attacker with write access there can still redirect a read to a file
     * outside {@code root} by swapping a symbolic link onto an intermediate directory of the resolved
     * path, or — where the operating system's {@code fs.protected_hardlinks} protection is disabled —
     * by a hard link or a rename onto the target. Such races are resolved by the operating system while
     * the file is opened and cannot be prevented here; protect the directory tree with filesystem
     * permissions.
     */
    static Predicate<Path> confinedTo(Path root) {
        requireNonNull(root, "root");
        return realPath -> {
            final Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                // An unresolvable root (e.g. it does not exist) cannot contain the path; fail closed.
                return false;
            }
            return realPath.startsWith(realRoot);
        };
    }
}
