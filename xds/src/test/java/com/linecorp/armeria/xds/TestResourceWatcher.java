/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import io.grpc.Status;

class TestResourceWatcher implements SnapshotWatcher<Snapshot<?>> {
    private static final Logger logger = LoggerFactory.getLogger(TestResourceWatcher.class);

    private final LinkedBlockingDeque<List<Object>> events = new LinkedBlockingDeque<>();

    @Override
    public void onError(XdsType type, Status error) {
        logger.info("onError: {}", error);
        events.add(ImmutableList.of("onError", error));
    }

    @Override
    public void onMissing(XdsType type, String resourceName) {
        logger.info("onMissing: {}", resourceName);
        events.add(ImmutableList.of("onMissing", ImmutableList.of(type, resourceName)));
    }

    @Override
    public void snapshotUpdated(Snapshot<?> newSnapshot) {
        logger.info("snapshotUpdated: {}", newSnapshot);
        events.add(ImmutableList.of("snapshotUpdated", newSnapshot));
    }

    List<Object> blockingMissing() {
        //noinspection unchecked
        return blockingFirst("onMissing", List.class);
    }

    <T> T blockingChanged(Class<T> clazz) {
        return blockingFirst("snapshotUpdated", clazz);
    }

    private <T> T blockingFirst(String type, Class<T> clazz) {
        final List<Object> objects;
        try {
            objects = events.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (objects == null) {
            logger.warn("Current event state: {}", events);
            throw new RuntimeException("Unable to find type: " + type);
        }
        if (!type.equals(objects.get(0))) {
            logger.warn("Current event state: {}", events);
            throw new IllegalStateException("Unexpected event: " + objects);
        }
        return clazz.cast(objects.get(1));
    }

    public BlockingDeque<List<Object>> events() {
        return events;
    }
}
