/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.eureka;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.eureka.Application;
import com.linecorp.armeria.internal.common.eureka.Applications;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.InstanceStatus;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.PortWrapper;
import com.linecorp.armeria.server.eureka.EurekaUpdatingListener;

import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A Eureka-based {@link EndpointGroup} implementation. This {@link EndpointGroup} retrieves the list of
 * {@link Endpoint}s from a Eureka registry.
 *
 * @see EurekaUpdatingListener
 */
public final class EurekaEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(EurekaEndpointGroup.class);

    private static final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.UNWRAP_ROOT_VALUE)
                              .setSerializationInclusion(Include.NON_NULL);

    private static final Predicate<InstanceInfo> allInstances = instanceInfo -> true;

    private static final String APPS = "/apps";
    private static final String VIPS = "/vips/";
    private static final String SVIPS = "/svips/";
    private static final String INSTANCES = "/instances/";

    /**
     * The {@link AttributeKey} that store eureka {@link InstanceInfo}.
     * Use {@link Endpoint#attr(AttributeKey)} to retrieve the value.
     */
    @UnstableApi
    public static final AttributeKey<InstanceInfo> INSTANCE_INFO = AttributeKey.valueOf("instanceInfo");

    /**
     * Returns a new {@link EurekaEndpointGroup} that retrieves the {@link Endpoint} list from the specified
     * {@code eurekaUri}.
     */
    public static EurekaEndpointGroup of(String eurekaUri) {
        return of(URI.create(requireNonNull(eurekaUri, "eurekaUri")));
    }

    /**
     * Returns a new {@link EurekaEndpointGroup} that retrieves the {@link Endpoint} list from the specified
     * {@code eurekaUri}.
     */
    public static EurekaEndpointGroup of(URI eurekaUri) {
        return new EurekaEndpointGroupBuilder(eurekaUri).build();
    }

    /**
     * Returns a new {@link EurekaEndpointGroup} that retrieves the {@link Endpoint} list from the specified
     * {@link EndpointGroup}.
     */
    public static EurekaEndpointGroup of(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        return new EurekaEndpointGroupBuilder(sessionProtocol, endpointGroup, null).build();
    }

    /**
     * Returns a new {@link EurekaEndpointGroup} that retrieves the {@link Endpoint} list from the specified
     * {@link EndpointGroup} under the specified {@code path}.
     */
    public static EurekaEndpointGroup of(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup, String path) {
        return new EurekaEndpointGroupBuilder(
                sessionProtocol, endpointGroup, requireNonNull(path, "path")).build();
    }

    /**
     * Returns a new {@link EurekaEndpointGroupBuilder} created with the specified {@code eurekaUri}.
     */
    public static EurekaEndpointGroupBuilder builder(String eurekaUri) {
        return builder(URI.create(requireNonNull(eurekaUri, "eurekaUri")));
    }

    /**
     * Returns a new {@link EurekaEndpointGroupBuilder} created with the specified {@code eurekaUri}.
     */
    public static EurekaEndpointGroupBuilder builder(URI eurekaUri) {
        return new EurekaEndpointGroupBuilder(eurekaUri);
    }

    /**
     * Returns a new {@link EurekaEndpointGroupBuilder} created with the specified {@link SessionProtocol} and
     * {@link EndpointGroup}.
     */
    public static EurekaEndpointGroupBuilder builder(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        return new EurekaEndpointGroupBuilder(sessionProtocol, endpointGroup, null);
    }

    /**
     * Returns a new {@link EurekaEndpointGroupBuilder} created with the specified {@link SessionProtocol},
     * {@link EndpointGroup} and path.
     */
    public static EurekaEndpointGroupBuilder builder(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup, String path) {
        return new EurekaEndpointGroupBuilder(sessionProtocol, endpointGroup, requireNonNull(path, "path"));
    }

    private final long registryFetchIntervalMillis;

    private final RequestHeaders requestHeaders;
    private final Function<byte[], List<Endpoint>> responseConverter;
    private final WebClient webClient;
    @Nullable
    private volatile ScheduledFuture<?> scheduledFuture;
    private volatile boolean closed;

    EurekaEndpointGroup(EndpointSelectionStrategy selectionStrategy,
                        WebClient webClient, long registryFetchIntervalMillis, @Nullable String appName,
                        @Nullable String instanceId, @Nullable String vipAddress,
                        @Nullable String secureVipAddress, @Nullable List<String> regions,
                        boolean instanceMetadataAsAttrs) {
        super(selectionStrategy);
        this.webClient = webClient;
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;

        final RequestHeadersBuilder headersBuilder = RequestHeaders.builder();
        headersBuilder.method(HttpMethod.GET);
        headersBuilder.accept(MediaType.JSON_UTF_8);
        responseConverter = responseConverter(headersBuilder, appName, instanceId,
                                              vipAddress, secureVipAddress, regions, instanceMetadataAsAttrs);
        requestHeaders = headersBuilder.build();

        webClient.options().factory().whenClosed().thenRun(this::closeAsync);
        fetchRegistry();
    }

    private void fetchRegistry() {
        final HttpResponse response;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            response = webClient.execute(requestHeaders);
            ctx = captor.get();
        }

        final EventLoop eventLoop = ctx.eventLoop().withoutContext();
        response.aggregateWithPooledObjects(eventLoop, ctx.alloc()).handle((aggregatedRes, cause) -> {
            try (HttpData content = aggregatedRes.content()) {
                if (closed) {
                    return null;
                }
                if (cause != null) {
                    logger.warn("Unexpected exception while fetching the registry from: {}." +
                                " (requestHeaders: {})", webClient.uri(), requestHeaders, cause);
                } else {
                    final HttpStatus status = aggregatedRes.status();
                    if (!status.isSuccess()) {
                        logger.warn("Unexpected response from: {}. (status: {}, content: {}, " +
                                    "requestHeaders: {})", webClient.uri(), status,
                                    aggregatedRes.contentUtf8(), requestHeaders);
                    } else {
                        try {
                            final List<Endpoint> endpoints = responseConverter.apply(content.array());
                            setEndpoints(endpoints);
                        } catch (Exception e) {
                            logger.warn("Unexpected exception while parsing a response from: {}. " +
                                        "(content: {}, responseConverter: {}, requestHeaders: {})",
                                        webClient.uri(), content.toStringUtf8(),
                                        responseConverter, requestHeaders, e);
                        }
                    }
                }
            }
            scheduledFuture = eventLoop.schedule(this::fetchRegistry,
                                                 registryFetchIntervalMillis, TimeUnit.MILLISECONDS);
            return null;
        });
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        closed = true;
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        super.doCloseAsync(future);
    }

    private static Function<byte[], List<Endpoint>> responseConverter(
            RequestHeadersBuilder builder, @Nullable String appName, @Nullable String instanceId,
            @Nullable String vipAddress, @Nullable String secureVipAddress, @Nullable List<String> regions,
            boolean instanceMetadataAsAttrs) {
        if (regions != null) {
            final Predicate<InstanceInfo> filter;
            final String path;
            boolean secureVip = false;
            if (vipAddress != null) {
                path = VIPS + vipAddress;
                filter = instanceInfo -> vipAddress.equals(instanceInfo.getVipAddress());
            } else if (secureVipAddress != null) {
                secureVip = true;
                path = SVIPS + secureVipAddress;
                filter = instanceInfo -> secureVipAddress.equals(instanceInfo.getSecureVipAddress());
            } else {
                // If regions is specified, we fetch all registry information and filter what we need because
                // some of the REST endpoints do not support regions query parameter.
                path = APPS;
                if (appName == null && instanceId == null) {
                    filter = allInstances;
                } else if (appName != null && instanceId != null) {
                    filter = instanceInfo -> appName.equals(instanceInfo.getAppName()) &&
                                             instanceId.equals(instanceInfo.getInstanceId());
                } else if (appName != null) {
                    filter = instanceInfo -> appName.equals(instanceInfo.getAppName());
                } else {
                    filter = instanceInfo -> instanceId.equals(instanceInfo.getInstanceId());
                }
            }
            final StringJoiner joiner = new StringJoiner(",");
            regions.forEach(joiner::add);
            final QueryParams queryParams = QueryParams.of("regions", joiner.toString());
            builder.path(path + '?' + queryParams.toQueryString());
            return new ApplicationsConverter(filter, secureVip, instanceMetadataAsAttrs);
        }

        if (vipAddress != null) {
            builder.path(VIPS + vipAddress);
            return new ApplicationsConverter(instanceMetadataAsAttrs);
        }

        if (secureVipAddress != null) {
            builder.path(SVIPS + secureVipAddress);
            return new ApplicationsConverter(allInstances, true, instanceMetadataAsAttrs);
        }

        if (appName == null && instanceId == null) {
            builder.path(APPS);
            return new ApplicationsConverter(instanceMetadataAsAttrs);
        }

        if (appName != null && instanceId != null) {
            builder.path(APPS + '/' + appName + '/' + instanceId);
            return new InstanceInfoConverter(instanceMetadataAsAttrs);
        }

        if (appName != null) {
            builder.path(APPS + '/' + appName);
            return new ApplicationConverter(instanceMetadataAsAttrs);
        }

        // instanceId is not null at this point.
        builder.path(INSTANCES + instanceId);
        return new InstanceInfoConverter(instanceMetadataAsAttrs);
    }

    private static class ApplicationsConverter implements Function<byte[], List<Endpoint>> {

        private final Predicate<InstanceInfo> filter;
        private final boolean secureVip;
        private final boolean instanceMetadataAsAttrs;

        ApplicationsConverter(boolean instanceMetadataAsAttrs) {
            this(allInstances, false, instanceMetadataAsAttrs);
        }

        ApplicationsConverter(Predicate<InstanceInfo> filter, boolean secureVip,
                              boolean instanceMetadataAsAttrs) {
            this.filter = filter;
            this.secureVip = secureVip;
            this.instanceMetadataAsAttrs = instanceMetadataAsAttrs;
        }

        @Override
        public List<Endpoint> apply(byte[] content) {
            try {
                final Set<Application> applications =
                        mapper.readValue(content, Applications.class).applications();
                return applications.stream()
                                   .map(application -> endpoints(application, filter, secureVip,
                                                                 instanceMetadataAsAttrs))
                                   .flatMap(List::stream)
                                   .collect(toImmutableList());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static List<Endpoint> endpoints(Application application, Predicate<InstanceInfo> filter,
                                            boolean secureVip, boolean instanceMetadataAsAttrs) {
        final Set<InstanceInfo> instances = application.instances();
        return instances.stream()
                        .filter(filter)
                        .filter(instanceInfo -> instanceInfo.getStatus() == InstanceStatus.UP)
                        .map(instanceInfo -> endpoint(instanceInfo, secureVip, instanceMetadataAsAttrs))
                        .collect(toImmutableList());
    }

    private static class ApplicationConverter implements Function<byte[], List<Endpoint>> {
        private final Predicate<InstanceInfo> filter;
        private final boolean instanceMetadataAsAttrs;

        ApplicationConverter(boolean instanceMetadataAsAttrs) {
            this(allInstances, instanceMetadataAsAttrs);
        }

        ApplicationConverter(Predicate<InstanceInfo> filter, boolean instanceMetadataAsAttrs) {
            this.filter = filter;
            this.instanceMetadataAsAttrs = instanceMetadataAsAttrs;
        }

        @Override
        public List<Endpoint> apply(byte[] content) {
            try {
                final Application application = mapper.readValue(content, Application.class);
                return endpoints(application, filter, false, instanceMetadataAsAttrs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class InstanceInfoConverter implements Function<byte[], List<Endpoint>> {
        private final boolean instanceMetadataAsAttrs;

        private InstanceInfoConverter(boolean instanceMetadataAsAttrs) {
            this.instanceMetadataAsAttrs = instanceMetadataAsAttrs;
        }

        @Override
        public List<Endpoint> apply(byte[] content) {
            try {
                return ImmutableList.of(endpoint(mapper.readValue(content, InstanceInfo.class), false,
                                                 instanceMetadataAsAttrs));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Endpoint endpoint(InstanceInfo instanceInfo, boolean secureVip,
                                     boolean instanceMetadataAsAttrs) {
        final String hostname = instanceInfo.getHostName();
        final PortWrapper portWrapper = instanceInfo.getPort();
        final int port;
        if (secureVip || !portWrapper.isEnabled()) {
            port = instanceInfo.getSecurePort().getPort();
        } else {
            port = portWrapper.getPort();
        }

        assert hostname != null;
        Endpoint endpoint = Endpoint.of(hostname, port);
        final String ipAddr = instanceInfo.getIpAddr();
        if (ipAddr != null && hostname != ipAddr) {
            endpoint = endpoint.withIpAddr(ipAddr);
        }
        if (instanceMetadataAsAttrs) {
            addInstanceMetadata(endpoint, instanceInfo);
        }

        return endpoint;
    }

    private static void addInstanceMetadata(Endpoint endpoint, InstanceInfo instanceInfo) {
        endpoint.setAttr(INSTANCE_INFO, instanceInfo);
        instanceInfo.getMetadata().forEach((key, value) -> {
            if (!"@class".equals(key)) {
                endpoint.setAttr(AttributeKey.valueOf(key), value);
            }
        });
    }
}
