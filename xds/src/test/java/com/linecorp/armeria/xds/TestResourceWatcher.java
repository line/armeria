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
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.Status;

class TestResourceWatcher implements SnapshotWatcher<Snapshot<?>> {
    private static final Logger logger = LoggerFactory.getLogger(TestResourceWatcher.class);

    private final LinkedBlockingDeque<List<Object>> events = new LinkedBlockingDeque<>();

    @Override
    public void onUpdate(@Nullable Snapshot<?> snapshot, @Nullable Throwable t) {
        if (snapshot == null) {
            if (t instanceof XdsResourceException) {
                final XdsResourceException xdsError = (XdsResourceException) t;
                if (t instanceof MissingXdsResourceException) {
                    logger.info("onMissing: {}", xdsError.name());
                    events.add(ImmutableList.of("onMissing", ImmutableList.of(xdsError.type(),
                                                                              xdsError.name())));
                } else {
                    logger.info("onError: {}", Status.fromThrowable(t));
                    events.add(ImmutableList.of("onError", Status.fromThrowable(t)));
                }
            } else {
                logger.info("onError: {}", t);
                events.add(ImmutableList.of("onError", t));
            }
            return;
        }
        logger.info("snapshotUpdated: {}", snapshot);
        events.add(ImmutableList.of("snapshotUpdated", snapshot));
    }

    List<Object> blockingMissing() {
        //noinspection unchecked
        return blockingFirst("onMissing", List.class);
    }

    <T> void batchAssertChanged(Class<T> clazz, Consumer<List<T>> assertion) throws Exception {
        Thread.sleep(1000);
        final ImmutableList.Builder<T> builder = ImmutableList.builder();
        while (!events.isEmpty()) {
            final T obj = blockingFirst("snapshotUpdated", clazz);
            builder.add(obj);
        }
        assertion.accept(builder.build());
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
