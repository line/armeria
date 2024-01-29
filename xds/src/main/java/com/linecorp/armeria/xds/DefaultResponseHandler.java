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

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Status;

final class DefaultResponseHandler implements XdsResponseHandler {

    private final SubscriberStorage storage;
    private static final Joiner errorMessageJoiner = Joiner.on('\n');

    DefaultResponseHandler(SubscriberStorage storage) {
        this.storage = storage;
    }

    @Override
    public <I extends Message, O extends XdsResource> void handleResponse(
            ResourceParser<I, O> resourceParser, DiscoveryResponse response, SotwXdsStream sender) {
        final ParsedResourcesHolder<O> holder =
                resourceParser.parseResources(response.getResourcesList());
        String errorDetail = null;
        if (holder.errors().isEmpty()) {
            sender.ackResponse(resourceParser.type(), response.getVersionInfo(), response.getNonce());
        } else {
            errorDetail = errorMessageJoiner.join(holder.errors());
            sender.nackResponse(resourceParser.type(), response.getNonce(), errorDetail);
        }

        final Map<String, XdsStreamSubscriber<O>> subscribedResources =
                storage.subscribers(resourceParser.type());
        for (Map.Entry<String, XdsStreamSubscriber<O>> entry : subscribedResources.entrySet()) {
            final String resourceName = entry.getKey();
            final XdsStreamSubscriber<O> subscriber = entry.getValue();

            if (holder.parsedResources().containsKey(resourceName)) {
                // Happy path: the resource updated successfully. Notify the watchers of the update.
                subscriber.onData(holder.parsedResources().get(resourceName));
                continue;
            }

            // Nothing else to do for incremental ADS resources.
            if (!resourceParser.isFullStateOfTheWorld()) {
                continue;
            }

            // Handle State of the World ADS: invalid resources.
            if (holder.invalidResources().contains(resourceName)) {
                // The resource is missing. Reuse the cached resource if possible.
                if (subscriber.data() == null) {
                    // No cached resource. Notify the watchers of an invalid update.
                    subscriber.onError(Status.UNAVAILABLE.withDescription(errorDetail));
                }
                continue;
            }

            // For State of the World services, notify watchers when their watched resource is missing
            // from the ADS update. Note that we can only do this if the resource update is coming from
            // the same xDS server that the ResourceSubscriber is subscribed to.
            subscriber.onAbsent();
        }
    }
}
