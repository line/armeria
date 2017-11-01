/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.dns;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.common.CommonPools;

import io.netty.channel.EventLoop;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS address queries (A and AAAA). This is useful
 * for environments where service discovery is handled using DNS - for example, Kubernetes uses SkyDNS for
 * service discovery.
 */
public class DnsAddressEndpointGroup extends DynamicEndpointGroup {

    /**
     * Creates a {@link DnsAddressEndpointGroup} with an unspecified port that schedules queries on a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()} every 1 second.
     *
     * @param hostname the hostname to query DNS queries for.
     */
    public static DnsAddressEndpointGroup of(String hostname) {
        return of(hostname, 0);
    }

    /**
     * Creates a {@link DnsAddressEndpointGroup} that schedules queries on a random {@link EventLoop} from
     * {@link CommonPools#workerGroup()} every 1 second.
     *
     * @param hostname the hostname to query DNS queries for.
     * @param defaultPort the port to use when the DNS answer does not contain one. {@code 0} indicates an
     *     unspecified port, meaning the port will use a protocol-specific well-defined port number
     *     (e.g., 80, 443).
     */
    public static DnsAddressEndpointGroup of(String hostname, int defaultPort) {
        return of(hostname, defaultPort, CommonPools.workerGroup().next());
    }

    /**
     * Creates a {@link DnsAddressEndpointGroup} that queries every 1 second.
     *
     * @param hostname the hostname to query DNS queries for.
     * @param defaultPort the port to use when the DNS answer does not contain one. {@code 0} indicates an
     *     unspecified port, meaning the port will use a protocol-specific well-defined port number
     *     (e.g., 80, 443).
     * @param eventLoop the {@link EventLoop} to schedule DNS queries on.
     */
    public static DnsAddressEndpointGroup of(String hostname, int defaultPort, EventLoop eventLoop) {
        return of(hostname, defaultPort, eventLoop, Duration.ofSeconds(1));
    }

    /**
     * Creates a {@link DnsAddressEndpointGroup}.
     *
     * @param hostname the hostname to query DNS queries for.
     * @param defaultPort the port to use when the DNS answer does not contain one. {@code 0} indicates an
     *     unspecified port, meaning the port will use a protocol-specific well-defined port number
     *     (e.g., 80, 443).
     * @param eventLoop the {@link EventLoop} to schedule DNS queries on.
     * @param queryInterval the {@link Duration} to query DNS at.
     */
    public static DnsAddressEndpointGroup of(String hostname, int defaultPort, EventLoop eventLoop,
                                             Duration queryInterval) {
        return new DnsAddressEndpointGroup(hostname, defaultPort,
                                           DnsEndpointGroupUtil.createResolverForEventLoop(eventLoop),
                                           eventLoop, queryInterval);
    }

    private static final Logger logger = LoggerFactory.getLogger(DnsAddressEndpointGroup.class);

    private final String hostname;
    private final int defaultPort;
    private final NameResolver<InetAddress> resolver;
    private final EventLoop eventLoop;
    private final Duration queryInterval;

    @Nullable
    private ScheduledFuture<?> scheduledFuture;

    @VisibleForTesting
    DnsAddressEndpointGroup(String hostname, int defaultPort, NameResolver<InetAddress> resolver,
                            EventLoop eventLoop, Duration queryInterval) {
        checkArgument(defaultPort >= 0 && defaultPort <= 65535, "defaultPort must be between 0 and 65535");
        this.hostname = requireNonNull(hostname, "hostname");
        this.defaultPort = defaultPort;
        this.resolver = requireNonNull(resolver, "resolver");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        this.queryInterval = requireNonNull(queryInterval, "queryInterval");
    }

    /**
     * Starts polling for service updates.
     */
    public void start() {
        checkState(scheduledFuture == null, "already started");
        scheduledFuture = eventLoop.scheduleAtFixedRate(this::query, 0, queryInterval.getSeconds(),
                                                        TimeUnit.SECONDS);
    }

    /**
     * Stops polling for service updates.
     */
    @Override
    public void close() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    @VisibleForTesting
    void query() {
        resolver.resolveAll(hostname).addListener(
                (Future<List<InetAddress>> future) -> {
                    if (future.cause() != null) {
                        logger.warn("Error resolving a domain name: {}", hostname, future.cause());
                        return;
                    }
                    List<Endpoint> endpoints =
                            future.getNow().stream()
                                  .map(InetAddress::getHostAddress)
                                  .map(ip -> defaultPort != 0 ? Endpoint.of(ip, defaultPort) : Endpoint.of(ip))
                                  .collect(toImmutableList());
                    List<Endpoint> currentEndpoints = endpoints();
                    if (!endpoints.equals(currentEndpoints)) {
                        setEndpoints(endpoints);
                    }
                });
    }
}
