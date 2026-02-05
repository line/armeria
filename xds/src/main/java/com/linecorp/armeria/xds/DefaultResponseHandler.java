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

import java.util.Map;

import com.google.common.base.Joiner;
import com.google.protobuf.Message;

import com.linecorp.armeria.xds.SotwXdsStream.ActualStream;

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

final class DefaultResponseHandler implements XdsResponseHandler {

    private final SubscriberStorage storage;
    private static final Joiner errorMessageJoiner = Joiner.on('\n');

    DefaultResponseHandler(SubscriberStorage storage) {
        this.storage = storage;
    }

    @Override
    public <I extends Message, O extends XdsResource> void handleResponse(
            ResourceParser<I, O> resourceParser, DiscoveryResponse response, ActualStream sender,
            ConfigSourceLifecycleObserver observer) {
        final long nextRevision = sender.versionManager()
                                        .nextRevision(resourceParser.type(), response.getVersionInfo());
        final ParsedResourcesHolder holder =
                resourceParser.parseResources(response.getResourcesList(), response.getVersionInfo(),
                                              nextRevision);
        final String errorDetails;
        if (holder.errors().isEmpty()) {
            sender.ackResponse(resourceParser.type(), response.getVersionInfo(), response.getNonce());
            // The version was updated, so we always update the cache in case a late watcher subscribed.
            // 1) A subscriber is added, and a stream is created.
            // 2) Immediately, the subscriber is removed but a response later updates the version.
            //    At this step, subscribers cannot be notified as there are no watchers
            //    and the value isn't cached.
            // 3) Afterward, a new subscriber is added - this subscriber doesn't see any cached values so the
            //    subscriber is in an indefinite waiting state until the version is incremented.
            storage.updateCache(resourceParser.type(), holder.parsedResources());
        } else {
            errorDetails = errorMessageJoiner.join(holder.errors());
            sender.nackResponse(resourceParser.type(), response.getNonce(), errorDetails);
        }
        observer.resourceUpdated(resourceParser.type(), response, holder.parsedResources());
        observer.resourceRejected(resourceParser.type(), response, holder.invalidResources());

        final Map<String, XdsStreamSubscriber<O>> subscribedResources =
                storage.subscribers(resourceParser.type());
        for (Map.Entry<String, XdsStreamSubscriber<O>> entry : subscribedResources.entrySet()) {
            final String resourceName = entry.getKey();
            final XdsStreamSubscriber<O> subscriber = entry.getValue();

            if (holder.parsedResources().containsKey(resourceName)) {
                // Happy path: the resource updated successfully. Notify the watchers of the update.
                notifyOnData(subscriber, holder, resourceName);
                continue;
            }

            final Throwable errorCause = holder.invalidResources().get(resourceName);
            if (errorCause != null) {
                subscriber.onError(resourceName, errorCause);
                continue;
            }

            // Handle State of the World ADS
            if (!resourceParser.isFullStateOfTheWorld()) {
                continue;
            }

            // For State of the World services, notify watchers when their watched resource is missing
            // from the ADS update. Note that we can only do this if the resource update is coming from
            // the same xDS server that the ResourceSubscriber is subscribed to.
            subscriber.onAbsent();
        }
    }

    @SuppressWarnings("unchecked")
    private static <O extends XdsResource> void notifyOnData(XdsStreamSubscriber<O> subscriber,
                                                             ParsedResourcesHolder holder,
                                                             String resourceName) {
        final O data = (O) holder.parsedResources().get(resourceName);
        assert data != null;
        subscriber.onData(data);
    }
}
