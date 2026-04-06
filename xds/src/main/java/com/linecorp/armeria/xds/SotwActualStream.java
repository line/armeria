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

import static com.linecorp.armeria.xds.XdsResourceParserUtil.fromTypeUrl;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.google.rpc.Code;
import com.google.rpc.Status;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest.Builder;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.EventExecutor;

final class SotwActualStream implements StreamObserver<DiscoveryResponse>, AdsXdsStream.ActualStream {

    private static final Logger logger = LoggerFactory.getLogger(SotwActualStream.class);

    // NACK backoff to prevent hot loops when the server keeps sending bad responses
    static final long NACK_BACKOFF_MILLIS = 3_000L;

    private final StreamObserver<DiscoveryRequest> requestObserver;
    private final AdsXdsStream owner;
    private final StateCoordinator stateCoordinator;
    private final EventExecutor eventLoop;
    private final ConfigSourceLifecycleObserver lifecycleObserver;
    private final Node node;

    private final Map<XdsType, String> noncesMap = new EnumMap<>(XdsType.class);
    private final Map<XdsType, String> lastAckedVersions = new EnumMap<>(XdsType.class);
    private boolean completed;

    SotwActualStream(SotwDiscoveryStub stub, AdsXdsStream owner,
                     StateCoordinator stateCoordinator,
                     EventExecutor eventLoop, ConfigSourceLifecycleObserver lifecycleObserver,
                     Node node) {
        this.owner = owner;
        this.stateCoordinator = stateCoordinator;
        this.eventLoop = eventLoop;
        this.lifecycleObserver = lifecycleObserver;
        this.node = node;
        requestObserver = stub.stream(this);
        lifecycleObserver.streamOpened();
    }

    void ackResponse(XdsType type, String versionInfo, String nonce) {
        noncesMap.put(type, nonce);
        lastAckedVersions.put(type, versionInfo);
        sendDiscoveryRequest(type, versionInfo, stateCoordinator.interestedResources(type),
                             nonce, null);
    }

    void nackResponse(XdsType type, String nonce, String errorDetail) {
        noncesMap.put(type, nonce);
        eventLoop.schedule(() -> sendDiscoveryRequest(type, lastAckedVersions.get(type),
                                                      stateCoordinator.interestedResources(type), nonce,
                                                      errorDetail),
                           NACK_BACKOFF_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void closeStream() {
        if (completed) {
            return;
        }
        completed = true;
        requestObserver.onCompleted();
    }

    @Override
    public void resourcesUpdated(XdsType type) {
        sendDiscoveryRequest(type);
    }

    private void sendDiscoveryRequest(XdsType type) {
        sendDiscoveryRequest(type, lastAckedVersions.get(type),
                             stateCoordinator.interestedResources(type), noncesMap.get(type), null);
    }

    private void sendDiscoveryRequest(XdsType type, @Nullable String version, Collection<String> resources,
                                      @Nullable String nonce, @Nullable String errorDetail) {
        if (completed) {
            return;
        }
        final Builder builder = DiscoveryRequest.newBuilder()
                                                .setTypeUrl(type.typeUrl())
                                                .setNode(node)
                                                .addAllResourceNames(resources);
        if (version != null) {
            builder.setVersionInfo(version);
        }
        if (nonce != null) {
            builder.setResponseNonce(nonce);
        }
        if (errorDetail != null) {
            builder.setErrorDetail(Status.newBuilder()
                                         .setCode(Code.INVALID_ARGUMENT_VALUE)
                                         .setMessage(errorDetail)
                                         .build());
        }
        final DiscoveryRequest request = builder.build();
        lifecycleObserver.requestSent(request);
        requestObserver.onNext(request);
    }

    @Override
    public void onNext(DiscoveryResponse value) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> onNext(value));
            return;
        }
        if (completed) {
            return;
        }
        lifecycleObserver.responseReceived(value);

        final ResourceParser<?, ?> resourceParser = fromTypeUrl(value.getTypeUrl());
        if (resourceParser == null) {
            logger.warn("XDS stream Received unexpected type: {}", value.getTypeUrl());
            return;
        }
        handleResponse(resourceParser, value);
    }

    @Override
    public void onError(Throwable throwable) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> onError(throwable));
            return;
        }
        completed = true;
        lifecycleObserver.streamError(throwable);
        owner.retryOrClose(true);
    }

    @Override
    public void onCompleted() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::onCompleted);
            return;
        }
        completed = true;
        lifecycleObserver.streamCompleted();
        owner.retryOrClose(false);
    }

    private <I extends Message, O extends XdsResource> void handleResponse(
            ResourceParser<I, O> resourceParser, DiscoveryResponse response) {
        final ParsedResourcesHolder holder =
                resourceParser.parseResources(response.getResourcesList(), response.getVersionInfo());

        if (!holder.errors().isEmpty()) {
            holder.invalidResources().forEach((name, error) -> stateCoordinator.onResourceError(
                    resourceParser.type(), name, error));
            lifecycleObserver.resourceRejected(resourceParser.type(), response, holder.invalidResources());

            nackResponse(resourceParser.type(), response.getNonce(),
                         String.join("\n", holder.errors()));
            return;
        }

        // first save data
        holder.parsedResources().forEach((name, resource) -> {
            if (resource instanceof XdsResource) {
                stateCoordinator.onResourceUpdated(resourceParser.type(), name, (XdsResource) resource);
            }
        });

        final boolean fullStateOfTheWorld = resourceParser.isFullStateOfTheWorld();
        if (fullStateOfTheWorld &&
            (resourceParser.type() == XdsType.LISTENER || resourceParser.type() == XdsType.CLUSTER)) {
            final Set<String> currentSubscribers =
                    stateCoordinator.interestedResources(resourceParser.type());
            if (!holder.parsedResources().isEmpty() || !currentSubscribers.isEmpty()) {
                for (String name : currentSubscribers) {
                    if (holder.parsedResources().containsKey(name) ||
                        holder.invalidResources().containsKey(name)) {
                        continue;
                    }
                    stateCoordinator.onResourceMissing(resourceParser.type(), name);
                }
            }
        }
        lifecycleObserver.resourceUpdated(resourceParser.type(), response, holder.parsedResources());
        // send the ack
        ackResponse(resourceParser.type(), response.getVersionInfo(), response.getNonce());
    }
}
