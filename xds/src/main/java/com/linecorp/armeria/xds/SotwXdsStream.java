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
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.rpc.Code;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest.Builder;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.EventExecutor;

final class SotwXdsStream implements XdsStream, XdsStreamState {

    private static final Logger logger = LoggerFactory.getLogger(SotwXdsStream.class);

    private final VersionManager versionManager = new VersionManager();
    private final SotwDiscoveryStub stub;
    private final Node node;
    private final Backoff backoff;
    private final EventExecutor eventLoop;
    private final XdsResponseHandler responseHandler;
    private final SubscriberStorage subscriberStorage;
    private int connBackoffAttempts = 1;

    // whether the stream is stopped explicitly by the user
    private boolean stopped;
    @Nullable
    @VisibleForTesting
    ActualStream actualStream;

    private final Set<XdsType> targetTypes;

    SotwXdsStream(SotwDiscoveryStub stub, Node node, Backoff backoff,
                  EventExecutor eventLoop, XdsResponseHandler responseHandler,
                  SubscriberStorage subscriberStorage) {
        this(stub, node, backoff, eventLoop, responseHandler, subscriberStorage,
             XdsType.discoverableTypes());
    }

    SotwXdsStream(SotwDiscoveryStub stub, Node node, Backoff backoff,
                  EventExecutor eventLoop, XdsResponseHandler responseHandler,
                  SubscriberStorage subscriberStorage, Set<XdsType> targetTypes) {
        this.stub = requireNonNull(stub, "stub");
        this.node = requireNonNull(node, "node");
        this.backoff = requireNonNull(backoff, "backoff");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.responseHandler = requireNonNull(responseHandler, "responseHandler");
        this.subscriberStorage = requireNonNull(subscriberStorage, "subscriberStorage");
        this.targetTypes = targetTypes;
    }

    @VisibleForTesting
    void start() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::start);
            return;
        }
        stopped = false;
        reset();
    }

    private void reset() {
        if (stopped) {
            return;
        }

        for (XdsType targetType : targetTypes) {
            // check the resource type actually has subscriptions.
            // otherwise, an unintentional onMissing callback may be received
            if (!subscriberStorage.resources(targetType).isEmpty()) {
                resourcesUpdated(targetType);
            }
        }
    }

    void stop() {
        stop(Status.CANCELLED.withDescription("shutdown").asException());
    }

    void stop(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> stop(throwable));
            return;
        }
        stopped = true;
        if (actualStream == null) {
            return;
        }
        actualStream.closeStream();
        actualStream = null;
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public void resourcesUpdated(XdsType type) {
        actualStream().sendDiscoveryRequest(type);
    }

    private ActualStream actualStream() {
        if (actualStream == null) {
            actualStream = new ActualStream(stub, this, versionManager, eventLoop,
                                            responseHandler, backoff, node);
        }
        return actualStream;
    }

    @Override
    public void retryOrClose(Status status, boolean closedByError) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> retryOrClose(status, closedByError));
            return;
        }
        if (stopped) {
            // don't reschedule automatically since the user explicitly closed the stream
            return;
        }
        actualStream = null;
        // wait backoff
        if (closedByError) {
            connBackoffAttempts++;
        } else {
            connBackoffAttempts = 1;
        }
        final long nextDelayMillis = backoff.nextDelayMillis(connBackoffAttempts);
        if (nextDelayMillis < 0) {
            return;
        }
        eventLoop.schedule(this::reset, nextDelayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public Collection<String> watchedResources(XdsType type) {
        return subscriberStorage.resources(type);
    }

    static class ActualStream implements StreamObserver<DiscoveryResponse> {

        private final StreamObserver<DiscoveryRequest> requestObserver;
        private final XdsStreamState xdsStreamState;
        private final VersionManager versionManager;
        private final EventExecutor eventLoop;
        private final XdsResponseHandler responseHandler;
        private final Backoff backoff;
        private final Node node;

        private int ackBackoffAttempts;
        private final Map<XdsType, String> noncesMap = new EnumMap<>(XdsType.class);
        boolean completed;

        ActualStream(SotwDiscoveryStub stub, XdsStreamState xdsStreamState, VersionManager versionManager,
                     EventExecutor eventLoop, XdsResponseHandler responseHandler, Backoff backoff, Node node) {
            this.xdsStreamState = xdsStreamState;
            this.versionManager = versionManager;
            this.eventLoop = eventLoop;
            this.responseHandler = responseHandler;
            this.backoff = backoff;
            this.node = node;
            requestObserver = stub.stream(this);
        }

        void ackResponse(XdsType type, String versionInfo, String nonce) {
            ackBackoffAttempts = 0;
            versionManager.updateVersion(type, versionInfo);
            sendDiscoveryRequest(type, versionInfo, xdsStreamState.watchedResources(type),
                                 nonce, null);
        }

        void nackResponse(XdsType type, String nonce, String errorDetail) {
            ackBackoffAttempts++;
            eventLoop.schedule(() -> sendDiscoveryRequest(type, versionManager.getVersion(type),
                                                          xdsStreamState.watchedResources(type), nonce,
                                                          errorDetail),
                               backoff.nextDelayMillis(ackBackoffAttempts), TimeUnit.MILLISECONDS);
        }

        VersionManager versionManager() {
            return versionManager;
        }

        void closeStream() {
            if (completed) {
                return;
            }
            completed = true;
            requestObserver.onCompleted();
        }

        void sendDiscoveryRequest(XdsType type) {
            sendDiscoveryRequest(type, versionManager.getVersion(type),
                                 xdsStreamState.watchedResources(type), noncesMap.get(type), null);
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
                builder.setErrorDetail(com.google.rpc.Status.newBuilder()
                                                            .setCode(Code.INVALID_ARGUMENT_VALUE)
                                                            .setMessage(errorDetail)
                                                            .build());
            }
            final DiscoveryRequest request = builder.build();
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

            final ResourceParser<?, ?> resourceParser = fromTypeUrl(value.getTypeUrl());
            if (resourceParser == null) {
                logger.warn("XDS stream Received unexpected type: {}", value.getTypeUrl());
                return;
            }
            noncesMap.put(resourceParser.type(), value.getNonce());
            responseHandler.handleResponse(resourceParser, value, this);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!eventLoop.inEventLoop()) {
                eventLoop.execute(() -> onError(throwable));
                return;
            }
            completed = true;
            requireNonNull(throwable, "throwable");
            xdsStreamState.retryOrClose(Status.fromThrowable(throwable), false);
        }

        @Override
        public void onCompleted() {
            if (!eventLoop.inEventLoop()) {
                eventLoop.execute(this::onCompleted);
                return;
            }
            completed = true;
            xdsStreamState.retryOrClose(Status.UNAVAILABLE.withDescription("Closed by server"), true);
        }
    }

    static class VersionManager {

        private final Map<XdsType, String> versionMap = new EnumMap<>(XdsType.class);
        private final Map<XdsType, Long> updatedCounts = new EnumMap<>(XdsType.class);

        void updateVersion(XdsType type, String version) {
            final String prevVersion = versionMap.get(type);
            if (Objects.equals(prevVersion, version)) {
                return;
            }
            versionMap.put(type, version);
            updatedCounts.put(type, updatedCounts.getOrDefault(type, 0L) + 1);
        }

        @Nullable
        String getVersion(XdsType type) {
            return versionMap.get(type);
        }

        long nextRevision(XdsType type, String version) {
            final String prevVersion = versionMap.get(type);
            final long revision = updatedCounts.getOrDefault(type, 0L);
            if (Objects.equals(prevVersion, version)) {
                return revision;
            }
            return revision + 1;
        }
    }
}
