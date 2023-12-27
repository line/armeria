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

import static java.util.Objects.requireNonNull;

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;

import io.grpc.Status;

class TestResourceWatcher<T extends Message> implements ResourceWatcher<ResourceHolder<T>> {
    private static final Logger logger = LoggerFactory.getLogger(TestResourceWatcher.class);

    private final Deque<List<Object>> events = new ConcurrentLinkedDeque<>();

    @Override
    public void onError(XdsType type, Status error) {
        logger.info("onError: {}", error);
        events.add(ImmutableList.of("onError", error));
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        logger.info("onResourceDoesNotExist: {}", resourceName);
        events.add(ImmutableList.of("onResourceDoesNotExist", resourceName));
    }

    @Override
    public void onChanged(ResourceHolder<T> update) {
        logger.info("onChanged: {}", update);
        events.add(ImmutableList.of("onChanged", update.data()));
    }

    Optional<Object> first(String type) {
        if (events.isEmpty()) {
            return Optional.empty();
        }
        final List<Object> objects = events.getFirst();
        if (type.equals(objects.get(0))) {
            return Optional.of(objects.get(1));
        }
        return Optional.empty();
    }

    List<Object> popFirst() {
        return requireNonNull(events.poll());
    }

    int eventSize() {
        return events.size();
    }

    public Deque<List<Object>> events() {
        return events;
    }
}
