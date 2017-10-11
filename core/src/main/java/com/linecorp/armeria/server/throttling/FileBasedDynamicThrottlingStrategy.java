/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.throttling;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Floats;
import com.sun.nio.file.SensitivityWatchEventModifier;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} to read and maintain a map from service API name to {@link ThrottlingStrategy}.
 * The {@link FileBasedDynamicThrottlingStrategy} will monitor a json mapping file for updates, whose format is
 * keys for every service API name with values is a string representation of {@link ThrottlingStrategy}.
 */
public class FileBasedDynamicThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T>
        implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(FileBasedDynamicThrottlingStrategy.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Splitter COLON_SPLITTER = Splitter.on(':').trimResults();

    private final WatchService watchService;
    private final Path settingFile;
    private final Thread watchThread;
    private volatile Map<String, ThrottlingStrategy<T>> strategies = ImmutableMap.of();

    /**
     * Creates a new instance.
     */
    public FileBasedDynamicThrottlingStrategy(String settingFile) {
        this.settingFile = FileSystems.getDefault().getPath(settingFile);
        try {
            watchService = FileSystems.getDefault().newWatchService();
            this.settingFile.getParent().register(watchService,
                                                  new WatchEvent.Kind[] {
                                                          StandardWatchEventKinds.ENTRY_CREATE,
                                                          StandardWatchEventKinds.ENTRY_MODIFY
                                                  },
                                                  SensitivityWatchEventModifier.HIGH);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start watch service.", e);
        }
        updateThrottlingSettings();
        watchThread = startWatching();
    }

    private Thread startWatching() {
        Thread thread = new Thread(() -> {
            try {
                while (true) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            // We only register "ENTRY_MODIFY" so the context is always a Path.
                            final Path changed = (Path) event.context();
                            if (changed.equals(settingFile.getFileName())) {
                                updateThrottlingSettings();
                                break;
                            }
                        }
                        if (!key.reset()) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        throw new IllegalStateException("Interrupted while taking from file.", e);
                    }
                }
            } catch (ClosedWatchServiceException ignored) {
                logger.info("Stopping hosts file watch thread.");
            } finally {
                try {
                    watchService.close();
                } catch (IOException e) {
                    logger.warn("Exception occurred during closing watchService.", e);
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void updateThrottlingSettings() {
        Map<String, String> newStrategies;
        try {
            newStrategies = OBJECT_MAPPER.readValue(settingFile.toFile(),
                                                    new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            logger.warn("Could not read hosts file", e);
            return;
        }

        strategies = newStrategies.entrySet().stream()
                                  .collect(toImmutableMap(Entry::getKey,
                                                          e -> convertToThrottlingStrategy(e.getValue())));
    }

    private ThrottlingStrategy<T> convertToThrottlingStrategy(String throttlingSpec) {
        List<String> spec = COLON_SPLITTER.splitToList(throttlingSpec);
        if (spec.isEmpty()) {
            logger.warn("Invalid throttlingSpec: {}", throttlingSpec);
            return ThrottlingStrategy.never();
        }
        switch (Ascii.toLowerCase(spec.get(0))) {
            case "always":
                return ThrottlingStrategy.always();
            case "never":
                return ThrottlingStrategy.never();
            case "sample":
                if (spec.size() != 2) {
                    logger.warn("Invalid sample throttlingSpec: {}", throttlingSpec);
                }
                float rate = Floats.tryParse(spec.get(1));
                return ThrottlingStrategy.of(
                        (ctx, req) -> completedFuture(rate > ThreadLocalRandom.current().nextFloat()));
            default:
                throw new IllegalArgumentException("Unknown key " + spec.get(0));
        }
    }

    @Override
    public CompletableFuture<Boolean> shouldThrottle(ServiceRequestContext ctx, T req) {
        String method = req instanceof RpcRequest ? ((RpcRequest) req).method() : ctx.method().name();
        return strategies.getOrDefault(method, ThrottlingStrategy.never()).shouldThrottle(ctx, req);
    }

    @Override
    public void close() throws Exception {
        watchService.close();
    }
}
