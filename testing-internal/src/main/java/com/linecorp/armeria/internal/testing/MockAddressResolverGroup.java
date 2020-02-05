/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * An {@link AddressResolverGroup} which returns an IP address resolved by a given resolver function.
 */
public final class MockAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    /**
     * Creates an {@link AddressResolverGroup} which always returns {@code 127.0.0.1} for any hostname.
     */
    public static MockAddressResolverGroup localhost() {
        return always("127.0.0.1");
    }

    /**
     * Creates an {@link AddressResolverGroup} which always returns the specified {@code ip} address
     * for any hostname.
     */
    public static MockAddressResolverGroup always(String ip) {
        requireNonNull(ip, "ip");
        checkArgument(NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip),
                      "not an IP address: %s", ip);
        try {
            final InetAddress resolved = InetAddress.getByName(ip);
            return new MockAddressResolverGroup(unused -> resolved);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Creates an {@link AddressResolverGroup} which resolves a given hostname to an {@link InetAddress}
     * using the specified {@code resolver}.
     */
    public static MockAddressResolverGroup of(Function<String, InetAddress> resolver) {
        return new MockAddressResolverGroup(requireNonNull(resolver, "resolver"));
    }

    private final Function<String, InetAddress> resolver;

    /**
     * Creates an {@link AddressResolverGroup} with the specified resolver function.
     *
     * @param resolver the resolver function which returns an {@link InetAddress} for the given hostname
     */
    private MockAddressResolverGroup(Function<String, InetAddress> resolver) {
        this.resolver = requireNonNull(resolver, "resolver");
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor eventExecutor) {
        return new InetSocketAddressResolver(eventExecutor, new InetNameResolver(eventExecutor) {
            @Override
            protected void doResolve(String hostname, Promise<InetAddress> promise) {
                try {
                    promise.setSuccess(resolver.apply(hostname));
                } catch (Exception e) {
                    promise.setFailure(e);
                }
            }

            @Override
            protected void doResolveAll(String hostname, Promise<List<InetAddress>> promise) {
                try {
                    promise.setSuccess(Collections.singletonList(resolver.apply(hostname)));
                } catch (Exception e) {
                    promise.setFailure(e);
                }
            }
        });
    }
}
