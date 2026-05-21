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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.Subscription;

final class CompositeXdsStream extends RefCountedStream<ParsedResources> implements XdsStream {

    private final Map<XdsType, XdsStream> streamMap;

    CompositeXdsStream(Function<XdsType, XdsStream> streamSupplier) {
        final ImmutableMap.Builder<XdsType, XdsStream> streamMapBuilder = ImmutableMap.builder();
        for (XdsType type : XdsType.discoverableTypes()) {
            streamMapBuilder.put(type, streamSupplier.apply(type));
        }
        streamMap = streamMapBuilder.build();
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<ParsedResources> watcher) {
        final List<Subscription> subscriptions = new ArrayList<>();
        for (XdsStream stream : streamMap.values()) {
            subscriptions.add(stream.subscribe(watcher));
        }
        return () -> {
            subscriptions.forEach(Subscription::close);
        };
    }
}
