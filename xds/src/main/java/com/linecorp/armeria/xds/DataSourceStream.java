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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.Cancellable;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.file.PathWatcher;

import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.DataSource.SpecifierCase;
import io.envoyproxy.envoy.config.core.v3.WatchedDirectory;

final class DataSourceStream extends RefCountedStream<Optional<ByteString>> {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceStream.class);

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
            return SnapshotStream.<ByteString>empty().subscribe(watcher);
        }
        if (dataSource.hasInlineBytes()) {
            final byte[] bytes = dataSource.getInlineBytes().toByteArray();
            return SnapshotStream.just(Optional.of(ByteString.copyFrom(bytes))).subscribe(watcher);
        }
        if (dataSource.hasInlineString()) {
            final byte[] bytes = dataSource.getInlineString().getBytes(StandardCharsets.UTF_8);
            return SnapshotStream.just(Optional.of(ByteString.copyFrom(bytes))).subscribe(watcher);
        }
        if (dataSource.hasEnvironmentVariable()) {
            final String envVar = dataSource.getEnvironmentVariable();
            final String envVarValue = System.getenv(envVar);
            if (envVarValue == null) {
                final IllegalArgumentException e = new IllegalArgumentException(
                        String.format("Environment variable '%s' not found", envVar));
                return SnapshotStream.<Optional<ByteString>>error(e).subscribe(watcher);
            }
            final byte[] bytes = envVarValue.getBytes(StandardCharsets.UTF_8);
            return SnapshotStream.just(Optional.of(ByteString.copyFrom(bytes))).subscribe(watcher);
        }
        if (dataSource.hasFilename()) {
            final String filename = dataSource.getFilename();
            final Path filePath = Paths.get(filename).toAbsolutePath();
            Path dirPath = filePath.getParent();
            if (watchedDirectory != WatchedDirectory.getDefaultInstance()) {
                dirPath = Paths.get(watchedDirectory.getPath());
            }
            if (dirPath == null) {
                final IllegalArgumentException e = new IllegalArgumentException(
                        String.format("'%s' is not a valid watched directory", filename));
                return SnapshotStream.<Optional<ByteString>>error(e).subscribe(watcher);
            }
            final Path dirPath0 = dirPath;
            final Future<Cancellable> f = CommonPools.blockingTaskExecutor().submit(
                    () -> context.watchService().register(dirPath0, PathWatcher.ofFile(filePath, bytes -> {
                        context.eventLoop().execute(
                                () -> watcher.onUpdate(Optional.of(ByteString.copyFrom(bytes)), null));
                    })));
            return () -> {
                // watch key cancellation can be blocking
                CommonPools.blockingTaskExecutor().execute(() -> {
                    try {
                        f.get().cancel();
                    } catch (Exception e) {
                        logger.warn("Exception while unregistering watch key for file: {}", filePath, e);
                    }
                });
            };
        }
        final IllegalArgumentException e = new IllegalArgumentException(
                String.format("Unexpected data source type '%s'", dataSource.getSpecifierCase()));
        return SnapshotStream.<Optional<ByteString>>error(e).subscribe(watcher);
    }
}
