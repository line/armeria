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

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.configsource.InterestedResources;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

final class ConfigSourceHandler implements SafeCloseable {

    private final StateCoordinator stateCoordinator;
    private final InterestPublisher interestPublisher;
    private final Subscription subscription;

    static ConfigSourceHandler of(StateCoordinator stateCoordinator, InterestPublisher interestPublisher,
                                  SnapshotStream<DiscoveryResponse> stream,
                                  SnapshotWatcher<Object> defaultWatcher) {
        return new ConfigSourceHandler(
                stateCoordinator, interestPublisher,
                stream.map(res -> parseResponse(res, stateCoordinator.extensionRegistry())),
                defaultWatcher);
    }

    ConfigSourceHandler(StateCoordinator stateCoordinator, InterestPublisher interestPublisher,
                        SnapshotStream<ParsedResources> stream, SnapshotWatcher<Object> defaultWatcher) {
        this.stateCoordinator = stateCoordinator;
        this.interestPublisher = interestPublisher;
        subscription = stream.subscribe((parsed, error) -> {
            if (error != null) {
                defaultWatcher.onUpdate(null, error);
                return;
            }
            if (parsed instanceof ParsedResources.SotwParsedResources) {
                apply((ParsedResources.SotwParsedResources) parsed);
            } else {
                assert parsed instanceof ParsedResources.DeltaParsedResources;
                apply((ParsedResources.DeltaParsedResources) parsed);
            }
        });
    }

    private void apply(ParsedResources.SotwParsedResources sotw) {
        final XdsType type = sotw.type();
        if (!sotw.invalidResources().isEmpty()) {
            sotw.invalidResources().forEach(
                    (name, cause) -> stateCoordinator.onResourceError(type, name, cause));
            return;
        }
        sotw.parsedResources().forEach((name, resource) -> {
            if (resource instanceof XdsResource) {
                stateCoordinator.onResourceUpdated(type, name, (XdsResource) resource);
            }
        });
        if (sotw.isFullStateOfTheWorld()) {
            for (String name : stateCoordinator.activeResources(type)) {
                if (!sotw.parsedResources().containsKey(name)) {
                    stateCoordinator.onResourceMissing(type, name);
                }
            }
        }
    }

    private void apply(ParsedResources.DeltaParsedResources delta) {
        final XdsType type = delta.type();
        if (!delta.invalidResources().isEmpty()) {
            delta.invalidResources().forEach(
                    (name, cause) -> stateCoordinator.onResourceError(type, name, cause));
            return;
        }
        delta.parsedResources().forEach((name, resource) -> {
            if (resource instanceof XdsResource) {
                stateCoordinator.onResourceUpdated(type, name, (XdsResource) resource);
            }
        });
        delta.removed().forEach(name -> stateCoordinator.onResourceMissing(delta.type(), name));
    }

    void addSubscriber(XdsType type, String resourceName, SnapshotWatcher<? extends XdsResource> watcher) {
        if (stateCoordinator.register(type, resourceName, watcher)) {
            interestPublisher.publish(
                    new InterestedResources(type, stateCoordinator.interestedResources(type)));
        }
    }

    boolean removeSubscriber(XdsType type, String resourceName,
                             SnapshotWatcher<? extends XdsResource> watcher) {
        if (stateCoordinator.unregister(type, resourceName, watcher)) {
            interestPublisher.publish(
                    new InterestedResources(type, stateCoordinator.interestedResources(type)));
        }
        return stateCoordinator.hasNoSubscribers();
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } finally {
            stateCoordinator.close();
        }
    }

    private static ParsedResources parseResponse(DiscoveryResponse response, XdsExtensionRegistry registry) {
        final String typeUrl = response.getTypeUrl();
        final ResourceParser<?, ?> parser = XdsResourceParserUtil.fromTypeUrl(typeUrl);
        if (parser == null) {
            throw new IllegalArgumentException("Unknown type URL in discovery response: " + typeUrl);
        }

        final ParsedResources.SotwParsedResources holder =
                parser.parseResources(response.getResourcesList(), registry, response.getVersionInfo());

        if (!holder.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "Failed to parse resource(s) from discovery response: " + holder);
        }

        return holder;
    }
}
