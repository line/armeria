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

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class PathConfigSourceLifecycleObserver {

    private static final Logger logger = LoggerFactory.getLogger(PathConfigSourceLifecycleObserver.class);

    private final Path filePath;
    private final MeterRegistry meterRegistry;
    private final Counter fileLoadedCounter;
    private final Counter fileParseErrorCounter;

    PathConfigSourceLifecycleObserver(Path filePath, MeterRegistry meterRegistry,
                                      MeterIdPrefix meterIdPrefix) {
        this.filePath = filePath;
        this.meterRegistry = meterRegistry;
        meterIdPrefix = meterIdPrefix.withTags("type", "path", "name", filePath.toString());
        fileLoadedCounter = meterRegistry.counter(meterIdPrefix.name("configsource.file.loaded"),
                                                  meterIdPrefix.tags());
        fileParseErrorCounter = meterRegistry.counter(meterIdPrefix.name("configsource.file.error"),
                                                      meterIdPrefix.tags());
    }

    void fileLoaded() {
        logger.debug("Path config source loaded: {}", filePath);
        fileLoadedCounter.increment();
    }

    void fileParseError(Throwable cause) {
        logger.warn("Failed to parse path config source file: {}", filePath, cause);
        fileParseErrorCounter.increment();
    }

    void close() {
        meterRegistry.remove(fileLoadedCounter);
        meterRegistry.remove(fileParseErrorCounter);
    }
}
