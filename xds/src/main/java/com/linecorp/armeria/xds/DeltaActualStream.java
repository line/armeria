/*
 * Copyright 2025 LY Corporation
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

import static com.linecorp.armeria.xds.XdsResourceParserUtil.fromTypeUrl;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.rpc.Code;
import com.google.rpc.Status;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.EventExecutor;

final class DeltaActualStream implements StreamObserver<DeltaDiscoveryResponse>, AdsXdsStream.ActualStream {

    private static final Logger logger = LoggerFactory.getLogger(DeltaActualStream.class);

    private final StreamObserver<DeltaDiscoveryRequest> requestObserver;
    private final AdsXdsStream owner;
    private final StateCoordinator stateCoordinator;
    private final EventExecutor eventLoop;
    private final ConfigSourceLifecycleObserver lifecycleObserver;
    private final Node node;

    private final ArrayDeque<PendingAck> ackQueue = new ArrayDeque<>();
    private final EnumSet<XdsType> pendingUpdates = EnumSet.noneOf(XdsType.class);
    // Types for which initial_resource_versions has already been sent on this stream.
    private final Set<XdsType> initialVersionsSent = EnumSet.noneOf(XdsType.class);
    private boolean completed;
    private boolean draining;

    DeltaActualStream(DeltaDiscoveryStub stub, AdsXdsStream owner, StateCoordinator stateCoordinator,
                      EventExecutor eventLoop, ConfigSourceLifecycleObserver lifecycleObserver, Node node) {
        this.owner = owner;
        this.stateCoordinator = stateCoordinator;
        this.eventLoop = eventLoop;
        this.lifecycleObserver = lifecycleObserver;
        this.node = node;
        requestObserver = stub.stream(this);
        lifecycleObserver.streamOpened();
    }

    void ackResponse(XdsType type, String nonce) {
        enqueueAck(type, nonce, null);
    }

    void nackResponse(XdsType type, String nonce, String errorDetail) {
        if (completed) {
            return;
        }
        eventLoop.schedule(() -> enqueueAck(type, nonce, errorDetail), SotwActualStream.NACK_BACKOFF_MILLIS,
                           TimeUnit.MILLISECONDS);
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
        enqueueDeltaRequest(type);
    }

    private void enqueueDeltaRequest(XdsType type) {
        if (completed) {
            return;
        }
        pendingUpdates.add(type);
        drainRequests();
    }

    private void enqueueAck(XdsType type, String nonce, @Nullable String errorDetail) {
        if (completed) {
            return;
        }
        ackQueue.add(new PendingAck(type, nonce, errorDetail));
        drainRequests();
    }

    private void drainRequests() {
        if (draining || completed) {
            return;
        }
        draining = true;
        try {
            while (!completed) {
                final PendingAck ack = ackQueue.poll();
                if (ack != null) {
                    sendDeltaRequest(ack.type, ack.nonce, ack.errorDetail);
                    continue;
                }
                final XdsType pendingType = nextPendingUpdate();
                if (pendingType == null) {
                    return;
                }
                pendingUpdates.remove(pendingType);
                sendDeltaRequest(pendingType, null, null);
            }
        } finally {
            draining = false;
        }
    }

    @Nullable
    private XdsType nextPendingUpdate() {
        for (XdsType type : XdsType.discoverableTypes()) {
            if (pendingUpdates.contains(type)) {
                return type;
            }
        }
        return null;
    }

    private void sendDeltaRequest(XdsType type, @Nullable String nonce, @Nullable String errorDetail) {
        if (completed) {
            return;
        }
        final Set<String> current = stateCoordinator.interestedResources(type);

        final Set<String> subscribe;
        final Set<String> unsubscribe;
        final boolean isFirstOnStream = !initialVersionsSent.contains(type);
        if (isFirstOnStream) {
            subscribe = current;
            unsubscribe = ImmutableSet.of();
        } else {
            // activeResources may include entries retained after unsubscribing, which can cause
            // spurious unsubscribe requests. However, subsequent requests will converge to a stable state.
            final Set<String> previous = stateCoordinator.activeResources(type);
            subscribe = new HashSet<>(current);
            subscribe.removeAll(previous);
            unsubscribe = new HashSet<>(previous);
            unsubscribe.removeAll(current);
        }

        final DeltaDiscoveryRequest.Builder builder =
                DeltaDiscoveryRequest.newBuilder()
                                     .setTypeUrl(type.typeUrl())
                                     .setNode(node)
                                     .addAllResourceNamesSubscribe(subscribe)
                                     .addAllResourceNamesUnsubscribe(unsubscribe);
        if (nonce != null) {
            builder.setResponseNonce(nonce);
        }
        if (errorDetail != null) {
            builder.setErrorDetail(Status.newBuilder()
                                         .setCode(Code.INVALID_ARGUMENT_VALUE)
                                         .setMessage(errorDetail)
                                         .build());
        }
        if (isFirstOnStream) {
            builder.putAllInitialResourceVersions(stateCoordinator.resourceVersions(type));
            initialVersionsSent.add(type);
        }
        final DeltaDiscoveryRequest request = builder.build();
        lifecycleObserver.requestSent(request);
        requestObserver.onNext(request);
    }

    private static final class PendingAck {

        private final XdsType type;
        private final String nonce;
        @Nullable
        private final String errorDetail;

        private PendingAck(XdsType type, String nonce, @Nullable String errorDetail) {
            this.type = type;
            this.nonce = nonce;
            this.errorDetail = errorDetail;
        }
    }

    @Override
    public void onNext(DeltaDiscoveryResponse value) {
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
            logger.warn("Delta XDS stream received unexpected type: {}", value.getTypeUrl());
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

    private void handleResponse(ResourceParser<?, ?> resourceParser, DeltaDiscoveryResponse response) {
        final XdsType type = resourceParser.type();
        final List<Resource> deltaResources = response.getResourcesList();
        final ParsedResourcesHolder holder =
                resourceParser.parseDeltaResources(deltaResources,
                                                   stateCoordinator.extensionRegistry());

        if (!holder.errors().isEmpty()) {
            holder.invalidResources().forEach((name, error) ->
                                                      stateCoordinator.onResourceError(type, name, error));
            lifecycleObserver.resourceRejected(type, response, holder.invalidResources());
            nackResponse(type, response.getNonce(), String.join("\n", holder.errors()));
            return;
        }
        lifecycleObserver.resourceUpdated(type, response, holder.parsedResources());

        holder.parsedResources().forEach((name, resource) -> {
            if (resource instanceof XdsResource) {
                stateCoordinator.onResourceUpdated(type, name, (XdsResource) resource);
            }
        });

        for (String removedName : response.getRemovedResourcesList()) {
            stateCoordinator.onResourceMissing(type, removedName);
        }

        // ack after processing so that the diff between interested - state is computed correctly
        ackResponse(type, response.getNonce());
    }
}
