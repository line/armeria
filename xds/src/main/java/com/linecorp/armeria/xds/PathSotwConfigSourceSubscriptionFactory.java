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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.common.Cancellable;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.file.PathWatcher;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.PathConfigSource;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.netty.util.concurrent.EventExecutor;

final class PathSotwConfigSourceSubscriptionFactory implements SotwConfigSourceSubscriptionFactory {

    static final String NAME = "armeria.config_source.path";

    private final DirectoryWatchService watchService;

    PathSotwConfigSourceSubscriptionFactory(DirectoryWatchService watchService) {
        this.watchService = watchService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ConfigSourceSubscription create(ConfigSource configSource,
                                           SotwSubscriptionCallbacks callbacks,
                                           EventExecutor eventLoop) {
        return new PathConfigSourceSubscription(
                configSource.getPathConfigSource(), watchService,
                callbacks, eventLoop);
    }

    static final class PathConfigSourceSubscription implements ConfigSourceSubscription {

        private static final Logger logger =
                LoggerFactory.getLogger(PathConfigSourceSubscription.class);

        private final Path filePath;
        private final Path watchDir;
        private final DirectoryWatchService watchService;
        private final SotwSubscriptionCallbacks callbacks;
        private final EventExecutor eventLoop;

        @Nullable
        private Cancellable watchCancellable;
        private boolean closed;

        PathConfigSourceSubscription(PathConfigSource pathConfigSource,
                                     DirectoryWatchService watchService,
                                     SotwSubscriptionCallbacks callbacks,
                                     EventExecutor eventLoop) {
            filePath = Paths.get(pathConfigSource.getPath()).toAbsolutePath();
            if (pathConfigSource.hasWatchedDirectory()) {
                watchDir = Paths.get(pathConfigSource.getWatchedDirectory().getPath()).toAbsolutePath();
            } else {
                watchDir = requireNonNull(filePath.getParent(), "filePath.getParent()");
            }
            this.watchService = watchService;
            this.callbacks = callbacks;
            this.eventLoop = eventLoop;

            startWatching();
        }

        private void startWatching() {
            watchCancellable = watchService.register(watchDir, PathWatcher.ofFile(filePath, bytes -> {
                eventLoop.execute(() -> parseAndPush(bytes));
            }));
        }

        private void parseAndPush(byte[] bytes) {
            if (closed) {
                return;
            }
            final DiscoveryResponse response;
            try {
                final DiscoveryResponse.Builder builder = DiscoveryResponse.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(new String(bytes), builder);
                response = builder.build();
            } catch (InvalidProtocolBufferException e) {
                logger.warn("Failed to parse path config source file as DiscoveryResponse: {}",
                            filePath, e);
                return;
            }
            callbacks.onDiscoveryResponse(response);
        }

        @Override
        public void updateInterests(XdsType type, Set<String> resourceNames) {
            // Path config source pushes all resources on every file change regardless of interests.
        }

        @Override
        public void close() {
            closed = true;
            if (watchCancellable != null) {
                CommonPools.blockingTaskExecutor().execute(watchCancellable::cancel);
            }
        }
    }
}
