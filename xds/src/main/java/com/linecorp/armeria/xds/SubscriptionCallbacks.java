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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

/**
 * Applies parsed xDS resources to a {@link StateCoordinator}, implementing both
 * {@link SotwSubscriptionCallbacks}.
 *
 * <p>This extracts the common "apply resources to state" logic from
 * {@link SotwActualStream#handleResponse} and {@link DeltaActualStream#handleResponse}
 * so it can be reused by custom config source stream implementations.
 */
final class SubscriptionCallbacks implements SotwSubscriptionCallbacks {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionCallbacks.class);

    private final StateCoordinator stateCoordinator;
    private final XdsExtensionRegistry extensionRegistry;

    SubscriptionCallbacks(StateCoordinator stateCoordinator,
                          XdsExtensionRegistry extensionRegistry) {
        this.stateCoordinator = stateCoordinator;
        this.extensionRegistry = extensionRegistry;
    }

    @Override
    public void onDiscoveryResponse(DiscoveryResponse response) {
        final String typeUrl = response.getTypeUrl();
        final ResourceParser<?, ?> parser = XdsResourceParserUtil.fromTypeUrl(typeUrl);
        if (parser == null) {
            logger.warn("Unknown type URL in discovery response: {}", typeUrl);
            return;
        }

        final XdsType type = parser.type();
        final ParsedResourcesHolder holder =
                parser.parseResources(response.getResourcesList(), extensionRegistry,
                                      response.getVersionInfo());
        if (!holder.errors().isEmpty()) {
            logger.warn("Failed to parse {} resource(s) from discovery response (type: {})",
                        holder.errors().size(), typeUrl);
        }
        onConfigUpdate(type, holder.parsedResources(),
                       holder.invalidResources(), response.getVersionInfo());
    }

    void onConfigUpdate(XdsType type, Map<String, Object> parsedResources,
                        Map<String, Throwable> invalidResources, String versionInfo) {
        // Report errors for invalid resources
        invalidResources.forEach((name, error) -> stateCoordinator.onResourceError(type, name, error));

        // Apply successfully parsed resources
        parsedResources.forEach((name, resource) -> {
            if (resource instanceof XdsResource) {
                stateCoordinator.onResourceUpdated(type, name, (XdsResource) resource);
            }
        });

        // SotW absent detection for full-state types (LDS/CDS)
        final ResourceParser<?, ?> resourceParser = XdsResourceParserUtil.fromType(type);
        assert resourceParser != null;
        final boolean fullStateOfTheWorld = resourceParser.isFullStateOfTheWorld();
        if (fullStateOfTheWorld) {
            final Set<String> activeResources = stateCoordinator.activeResources(resourceParser.type());
            for (String name : activeResources) {
                if (parsedResources.containsKey(name)) {
                    continue;
                }
                stateCoordinator.onResourceMissing(resourceParser.type(), name);
            }
        } else {
            // A limitation of sotw - we can't know if resources should be removed.
        }
    }

    void onConfigUpdate(XdsType type, Map<String, Object> parsedResources,
                        Map<String, Throwable> invalidResources,
                        List<String> removedResources, String systemVersionInfo) {
        // Report errors for invalid resources
        invalidResources.forEach((name, error) -> stateCoordinator.onResourceError(type, name, error));

        // Apply successfully parsed resources
        parsedResources.forEach((name, resource) -> {
            if (resource instanceof XdsResource) {
                stateCoordinator.onResourceUpdated(type, name, (XdsResource) resource);
            }
        });

        // Apply removals
        for (String removedName : removedResources) {
            stateCoordinator.onResourceMissing(type, removedName);
        }
    }
}
