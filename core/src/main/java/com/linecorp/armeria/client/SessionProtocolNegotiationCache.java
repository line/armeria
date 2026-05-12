/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.StampedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.LruMap;
import com.linecorp.armeria.internal.common.util.DomainSocketUtil;
import com.linecorp.armeria.internal.common.util.IpAddrUtil;

/**
 * Keeps the recent {@link SessionProtocol} negotiation failures. It is an LRU cache which keeps at most
 * 64k 'host name + port' pairs.
 */
public final class SessionProtocolNegotiationCache {

    private static final Logger logger = LoggerFactory.getLogger(SessionProtocolNegotiationCache.class);

    private static final StampedLock lock = new StampedLock();
    private static final Map<String, CacheEntry> cache = new LruMap<String, CacheEntry>(65536) {
        private static final long serialVersionUID = -2506868886873712772L;

        @Override
        protected boolean removeEldestEntry(Entry<String, CacheEntry> eldest) {
            final boolean remove = super.removeEldestEntry(eldest);
            if (remove) {
                logger.debug("Evicted: '{}' does not support {}", eldest.getKey(), eldest.getValue());
            }

            return remove;
        }
    };

    /**
     * Returns {@code true} if the specified {@link Endpoint} is known to have no support for
     * the specified {@link SessionProtocol}.
     */
    public static boolean isUnsupported(Endpoint endpoint, SessionProtocol protocol) {
        requireNonNull(endpoint, "endpoint");
        requireNonNull(protocol, "protocol");

        return isUnsupported(key(endpoint, protocol), protocol);
    }

    /**
     * Returns {@code true} if the specified {@code remoteAddress} is known to have no support for
     * the specified {@link SessionProtocol}.
     */
    public static boolean isUnsupported(SocketAddress remoteAddress, SessionProtocol protocol) {
        requireNonNull(remoteAddress, "remoteAddress");
        requireNonNull(protocol, "protocol");
        return isUnsupported(key(remoteAddress), protocol);
    }

    /**
     * Returns {@code true} if the specified {@link HttpPreference} is known to be unsupported for the given
     * {@code remoteAddress}.
     */
    @UnstableApi
    public static boolean isUnsupported(SocketAddress remoteAddress, HttpPreference preference) {
        requireNonNull(remoteAddress, "remoteAddress");
        requireNonNull(preference, "preference");
        final CacheEntry e;
        final String key = key(remoteAddress);
        final long stamp = lock.readLock();
        try {
            e = cache.get(key);
        } finally {
            lock.unlockRead(stamp);
        }

        if (e == null) {
            return false;
        }
        switch (preference) {
            case HTTP1_REQUIRED:
                return false;
            case HTTP2_REQUIRED:
                preference = HttpPreference.PREFACE;
                // fall through
            case PREFACE:
            case UPGRADE:
                return e.isUnsupported(preference);
        }
        throw new Error();
    }

    private static boolean isUnsupported(String key, SessionProtocol protocol) {
        if (!isCacheableProtocol(protocol)) {
            return false;
        }
        final CacheEntry e;
        final long stamp = lock.readLock();
        try {
            e = cache.get(key);
        } finally {
            lock.unlockRead(stamp);
        }

        if (e == null) {
            // Can't tell if it's unsupported
            return false;
        }
        return e.h2cUnsupported();
    }

    private static boolean isCacheableProtocol(SessionProtocol protocol) {
        // TLS negotiation (ALPN) can differ per connection so caching is unreliable.
        // HTTP, H1C is always supported
        return protocol == SessionProtocol.H2C;
    }

    /**
     * Updates the cache with the information that the specified {@code remoteAddress} does not support
     * the specified {@link SessionProtocol}.
     */
    public static void setUnsupported(SocketAddress remoteAddress, SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");
        if (!isCacheableProtocol(protocol)) {
            return;
        }
        final String key = key(remoteAddress);
        final CacheEntry e = getOrCreate(key);

        if (e.addUnsupported(HttpPreference.PREFACE) |
            e.addUnsupported(HttpPreference.UPGRADE)) {
            logger.debug("Updated: '{}' does not support {}", key, e);
        }
    }

    /**
     * Updates the cache with the information that the specified {@link HttpPreference} is not supported
     * by the given {@code remoteAddress}.
     */
    @UnstableApi
    public static void setUnsupported(SocketAddress remoteAddress, HttpPreference preference) {
        requireNonNull(remoteAddress, "remoteAddress");
        requireNonNull(preference, "preference");
        switch (preference) {
            case HTTP1_REQUIRED:
                return;
            case HTTP2_REQUIRED:
                preference = HttpPreference.PREFACE;
        }

        final String key = key(remoteAddress);
        final CacheEntry e = getOrCreate(key);

        if (e.addUnsupported(preference)) {
            logger.debug("Updated: '{}' does not support {}", key, e);
        }
    }

    /**
     * Clears the cache.
     */
    public static void clear() {
        int size;
        long stamp = lock.readLock();
        try {
            size = cache.size();
            if (size == 0) {
                return;
            }

            stamp = convertToWriteLock(stamp);
            size = cache.size();
            cache.clear();
        } finally {
            lock.unlock(stamp);
        }

        if (size != 0 && logger.isDebugEnabled()) {
            if (size != 1) {
                logger.debug("Cleared: {} entries", size);
            } else {
                logger.debug("Cleared: 1 entry");
            }
        }
    }

    private static CacheEntry getOrCreate(String key) {
        long stamp = lock.readLock();
        try {
            final CacheEntry entry = cache.get(key);
            if (entry != null) {
                return entry;
            }

            stamp = convertToWriteLock(stamp);

            return cache.computeIfAbsent(key, CacheEntry::new);
        } finally {
            lock.unlock(stamp);
        }
    }

    @VisibleForTesting
    static String key(Endpoint endpoint, SessionProtocol protocol) {
        if (endpoint.isDomainSocket()) {
            return endpoint.host();
        } else {
            return endpoint.host() + '|' + endpoint.port(protocol.defaultPort());
        }
    }

    @VisibleForTesting
    static String key(SocketAddress remoteAddress) {
        if (remoteAddress instanceof DomainSocketAddress) {
            return ((DomainSocketAddress) remoteAddress).authority();
        }

        if (remoteAddress instanceof InetSocketAddress) {
            final InetSocketAddress raddr = (InetSocketAddress) remoteAddress;
            final String hostOrIpAddr = raddr.getHostString();
            final String normalizedIpAddr = IpAddrUtil.normalize(hostOrIpAddr);
            if (normalizedIpAddr != null) {
                return normalizedIpAddr + '|' + raddr.getPort();
            } else {
                return hostOrIpAddr + '|' + raddr.getPort();
            }
        }

        if (remoteAddress instanceof io.netty.channel.unix.DomainSocketAddress) {
            return DomainSocketUtil.toAuthority(
                    ((io.netty.channel.unix.DomainSocketAddress) remoteAddress).path());
        }

        throw new IllegalArgumentException(
                "unsupported address type: " + remoteAddress.getClass().getName() +
                " (expected: InetSocketAddress or DomainSocketAddress)");
    }

    private static long convertToWriteLock(long stamp) {
        final long writeStamp = lock.tryConvertToWriteLock(stamp);
        if (writeStamp == 0L) {
            lock.unlockRead(stamp);
            stamp = lock.writeLock();
        } else {
            stamp = writeStamp;
        }
        return stamp;
    }

    private static final class CacheEntry {
        private volatile Set<HttpPreference> failedPreferences = ImmutableSet.of();

        CacheEntry(@SuppressWarnings("unused") String key) {
            // Key is unused. It's just here to simplify the Map.computeIfAbsent() call in getOrCreate().
        }

        private boolean addUnsupported(HttpPreference preference) {
            assert preference == HttpPreference.PREFACE || preference == HttpPreference.UPGRADE;
            final Set<HttpPreference> current = failedPreferences;
            if (current.contains(preference)) {
                return false;
            }
            failedPreferences = ImmutableSet.<HttpPreference>builder()
                                            .addAll(current)
                                            .add(preference)
                                            .build();
            return true;
        }

        private boolean isUnsupported(HttpPreference preference) {
            return failedPreferences.contains(preference);
        }

        private boolean h2cUnsupported() {
            return failedPreferences.contains(HttpPreference.PREFACE) &&
                   failedPreferences.contains(HttpPreference.UPGRADE);
        }

        @Override
        public String toString() {
            return failedPreferences.toString();
        }
    }

    private SessionProtocolNegotiationCache() {}
}
