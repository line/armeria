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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import com.linecorp.armeria.common.Cancellable;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.file.PathWatcher;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.PathConfigSource;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

final class PathSotwConfigSourceSubscriptionFactory implements SotwConfigSourceSubscriptionFactory {

    static final String NAME = "armeria.config_source.path";

    private final DirectoryWatchService watchService;
    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;

    PathSotwConfigSourceSubscriptionFactory(DirectoryWatchService watchService,
                                            MeterRegistry meterRegistry,
                                            MeterIdPrefix meterIdPrefix) {
        this.watchService = watchService;
        this.meterRegistry = meterRegistry;
        this.meterIdPrefix = meterIdPrefix;
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
                callbacks, eventLoop, meterRegistry, meterIdPrefix);
    }

    static final class PathConfigSourceSubscription implements ConfigSourceSubscription {

        private final Path filePath;
        private final Path watchDir;
        private final DirectoryWatchService watchService;
        private final SotwSubscriptionCallbacks callbacks;
        private final EventExecutor eventLoop;
        private final PathConfigSourceLifecycleObserver lifecycleObserver;

        @Nullable
        private Cancellable watchCancellable;
        private boolean closed;

        PathConfigSourceSubscription(PathConfigSource pathConfigSource,
                                     DirectoryWatchService watchService,
                                     SotwSubscriptionCallbacks callbacks,
                                     EventExecutor eventLoop, MeterRegistry meterRegistry,
                                     MeterIdPrefix meterIdPrefix) {
            filePath = Paths.get(pathConfigSource.getPath()).toAbsolutePath();
            if (pathConfigSource.hasWatchedDirectory()) {
                watchDir = Paths.get(pathConfigSource.getWatchedDirectory().getPath()).toAbsolutePath();
            } else {
                watchDir = requireNonNull(filePath.getParent(), "filePath.getParent()");
            }
            this.watchService = watchService;
            this.callbacks = callbacks;
            this.eventLoop = eventLoop;
            lifecycleObserver = new PathConfigSourceLifecycleObserver(filePath, meterRegistry, meterIdPrefix);

            startWatching();
        }

        private void startWatching() {
            CommonPools.blockingTaskExecutor().execute(() -> {
                final Cancellable cancellable;
                try {
                    cancellable = watchService.register(
                            watchDir, PathWatcher.ofFile(filePath, bytes -> {
                                eventLoop.execute(() -> parseAndPush(bytes));
                            }));
                } catch (Exception e) {
                    lifecycleObserver.fileParseError(e);
                    lifecycleObserver.close();
                    return;
                }
                eventLoop.execute(() -> {
                    if (closed) {
                        close0(cancellable);
                    } else {
                        watchCancellable = cancellable;
                    }
                });
            });
        }

        private void parseAndPush(byte[] bytes) {
            if (closed) {
                return;
            }
            final DiscoveryResponse response;
            try {
                final String content = new String(bytes, StandardCharsets.UTF_8);
                response = XdsResourceReader.from(content, DiscoveryResponse.class);
            } catch (Exception e) {
                lifecycleObserver.fileParseError(e);
                return;
            }
            lifecycleObserver.fileLoaded();
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
                close0(watchCancellable);
            }
        }

        private void close0(Cancellable watchCancellable) {
            lifecycleObserver.close();
            CommonPools.blockingTaskExecutor().execute(watchCancellable::cancel);
        }
    }
}
