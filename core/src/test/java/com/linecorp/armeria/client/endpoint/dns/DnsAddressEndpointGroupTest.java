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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.testing.internal.AnticipatedException;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.resolver.NameResolver;

public class DnsAddressEndpointGroupTest {

    private static final List<InetAddress> ADDRESSES;

    static {
        try {
            ADDRESSES = ImmutableList.of(
                    InetAddress.getByName("1.2.3.4"),
                    InetAddress.getByName("2.3.4.5"));
        } catch (UnknownHostException e) {
            throw new Error(e);
        }
    }

    private static final List<Endpoint> ENDPOINTS_NO_PORT =
            ImmutableList.of(
                    Endpoint.of("1.2.3.4"),
                    Endpoint.of("2.3.4.5"));

    private static final List<Endpoint> ENDPOINTS_PORT =
            ImmutableList.of(
                    Endpoint.of("1.2.3.4", 8080),
                    Endpoint.of("2.3.4.5", 8080));

    private static EventLoop EVENT_LOOP;

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private NameResolver<InetAddress> resolver;

    private DnsAddressEndpointGroup endpointGroup;

    @BeforeClass
    public static void setUpEventLoop() {
        EVENT_LOOP = new DefaultEventLoop(Executors.newSingleThreadExecutor());
    }

    @AfterClass
    public static void tearDownEventLoop() {
        EVENT_LOOP.shutdownGracefully(0, 10, TimeUnit.SECONDS);
    }

    @Before
    public void setUp() {
        when(resolver.resolveAll("armeria.com")).thenReturn(
                EVENT_LOOP.newSucceededFuture(ADDRESSES));
    }

    @Test
    public void noDefaultPort() {
        endpointGroup = new DnsAddressEndpointGroup(
                "armeria.com",
                0,
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        AtomicReference<List<Endpoint>> endpointsListener = new AtomicReference<>();
        endpointGroup.addListener(endpointsListener::set);
        assertThat(endpointGroup.endpoints()).isEmpty();
        assertThat(endpointsListener).hasValue(null);
        endpointGroup.query();
        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS_NO_PORT));
        assertThat(endpointsListener).hasValue(ENDPOINTS_NO_PORT);
    }

    @Test
    public void defaultPort() {
        endpointGroup = new DnsAddressEndpointGroup(
                "armeria.com",
                8080,
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        endpointGroup.query();
        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS_PORT));
    }

    @Test
    public void unexpectedError() throws Exception {
        endpointGroup = new DnsAddressEndpointGroup(
                "armeria.com",
                0,
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        endpointGroup.query();
        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS_NO_PORT));
        when(resolver.resolveAll("armeria.com")).thenReturn(
                EVENT_LOOP.newFailedFuture(new AnticipatedException("failed")));
        endpointGroup.query();
        // Unexpected errors ignored, do a small sleep to give time for the future to resolve.
        Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS_NO_PORT);
    }

    @Test
    public void startQuerying() {
        endpointGroup = new DnsAddressEndpointGroup(
                "armeria.com",
                0,
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        assertThat(endpointGroup.endpoints()).isEmpty();
        endpointGroup.start();
        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS_NO_PORT));
        when(resolver.resolveAll("armeria.com")).thenReturn(
                EVENT_LOOP.newSucceededFuture(ImmutableList.of(ADDRESSES.get(0))));
        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactly(ENDPOINTS_NO_PORT.get(0)));
        endpointGroup.close();
    }
}
