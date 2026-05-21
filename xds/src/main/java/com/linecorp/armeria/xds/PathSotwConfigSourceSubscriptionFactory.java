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

import com.linecorp.armeria.common.Cancellable;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.file.PathWatcher;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.configsource.InterestedResources;
import com.linecorp.armeria.xds.configsource.SotwConfigSourceSubscriptionFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

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
    public SnapshotStream<DiscoveryResponse> create(ConfigSource configSource,
                                                    FactoryContext factoryContext,
                                                    SnapshotStream<InterestedResources> interestedResources) {
        return new PathConfigSourceSubscription(configSource.getPathConfigSource(), watchService,
                                                factoryContext.eventLoop(), meterRegistry, meterIdPrefix);
    }

    static final class PathConfigSourceSubscription extends RefCountedStream<DiscoveryResponse> {

        private final Path filePath;
        private final Path watchDir;
        private final DirectoryWatchService watchService;
        private final EventExecutor eventLoop;
        private final PathConfigSourceLifecycleObserver lifecycleObserver;
        @Nullable
        private Cancellable watchCancellable;
        private boolean closed;

        PathConfigSourceSubscription(PathConfigSource pathConfigSource,
                                     DirectoryWatchService watchService,
                                     EventExecutor eventLoop, MeterRegistry meterRegistry,
                                     MeterIdPrefix meterIdPrefix) {
            filePath = Paths.get(pathConfigSource.getPath()).toAbsolutePath();
            if (pathConfigSource.hasWatchedDirectory()) {
                watchDir = Paths.get(pathConfigSource.getWatchedDirectory().getPath()).toAbsolutePath();
            } else {
                watchDir = requireNonNull(filePath.getParent(), "filePath.getParent()");
            }
            this.watchService = watchService;
            this.eventLoop = eventLoop;
            lifecycleObserver = new PathConfigSourceLifecycleObserver(filePath, meterRegistry, meterIdPrefix);
        }

        private void startWatching() {
            CommonPools.blockingTaskExecutor().execute(() -> {
                final Cancellable cancellable;
                try {
                    cancellable = watchService.register(
                            watchDir, PathWatcher.ofFile(filePath, bytes -> {
                                eventLoop.execute(() -> parseAndEmit(bytes));
                            }));
                } catch (Exception e) {
                    lifecycleObserver.fileParseError(e);
                    emit(null, e);
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

        private void parseAndEmit(byte[] bytes) {
            if (closed) {
                return;
            }
            final DiscoveryResponse response;
            try {
                final String content = new String(bytes, StandardCharsets.UTF_8);
                response = XdsResourceReader.from(content, DiscoveryResponse.class);
            } catch (Exception e) {
                lifecycleObserver.fileParseError(e);
                emit(null, e);
                return;
            }
            lifecycleObserver.fileLoaded();
            emit(response, null);
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<DiscoveryResponse> watcher) {
            startWatching();
            return () -> {
                closed = true;
                if (watchCancellable != null) {
                    close0(watchCancellable);
                }
            };
        }

        private void close0(Cancellable watchCancellable) {
            lifecycleObserver.close();
            CommonPools.blockingTaskExecutor().execute(watchCancellable::cancel);
        }
    }
}
