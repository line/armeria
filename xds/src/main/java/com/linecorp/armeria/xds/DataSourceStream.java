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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.file.DirectoryWatcher.fileWatcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.file.WatchKey;

import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.DataSource.SpecifierCase;
import io.envoyproxy.envoy.config.core.v3.WatchedDirectory;

final class DataSourceStream extends RefCountedStream<Optional<ByteString>> {

    private final DataSource dataSource;
    private final WatchedDirectory watchedDirectory;
    private final SubscriptionContext context;

    DataSourceStream(DataSource dataSource, WatchedDirectory watchedDirectory,
                     SubscriptionContext context) {
        this.dataSource = dataSource;
        this.watchedDirectory = watchedDirectory;
        this.context = context;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<Optional<ByteString>> watcher) {
        if (dataSource.getSpecifierCase() == SpecifierCase.SPECIFIER_NOT_SET) {
            watcher.onUpdate(Optional.empty(), null);
            return () -> {};
        }
        final WatchKey watchKey;
        if (dataSource.hasInlineBytes()) {
            final byte[] bytes = dataSource.getInlineBytes().toByteArray();
            watcher.onUpdate(Optional.of(ByteString.copyFrom(bytes)), null);
            watchKey = null;
        } else if (dataSource.hasInlineString()) {
            final byte[] bytes = dataSource.getInlineString().getBytes(StandardCharsets.UTF_8);
            watcher.onUpdate(Optional.of(ByteString.copyFrom(bytes)), null);
            watchKey = null;
        } else if (dataSource.hasEnvironmentVariable()) {
            final String envVar = dataSource.getEnvironmentVariable();
            final String envVarValue = System.getenv(envVar);
            checkArgument(envVarValue != null, "Environment variable not set for '%s'", envVar);
            final byte[] bytes = envVarValue.getBytes(StandardCharsets.UTF_8);
            watcher.onUpdate(Optional.of(ByteString.copyFrom(bytes)), null);
            watchKey = null;
        } else if (dataSource.hasFilename()) {
            final String filename = dataSource.getFilename();
            final Path filePath = Paths.get(filename).toAbsolutePath();
            Path dirPath = filePath.getParent();
            if (watchedDirectory != WatchedDirectory.getDefaultInstance()) {
                dirPath = Paths.get(watchedDirectory.getPath());
            }
            checkArgument(dirPath != null,
                          "Specified path doesn't have a watchable directory '%s'", filename);
            watchKey = context.watchService().register(dirPath, fileWatcher(filePath, bytes -> {
                context.eventLoop().execute(() -> watcher.onUpdate(
                        Optional.of(ByteString.copyFrom(bytes)), null));
            }));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported data source type: " + dataSource.getSpecifierCase());
        }
        return () -> {
            if (watchKey != null) {
                watchKey.cancel();
            }
        };
    }
}
