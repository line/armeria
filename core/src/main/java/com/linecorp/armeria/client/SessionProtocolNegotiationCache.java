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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.StampedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.LruMap;

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
        return isUnsupported(key(endpoint), protocol);
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

    private static boolean isUnsupported(String key, SessionProtocol protocol) {
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

        return e.isUnsupported(protocol);
    }

    /**
     * Updates the cache with the information that the specified {@code remoteAddress} does not support
     * the specified {@link SessionProtocol}.
     */
    public static void setUnsupported(SocketAddress remoteAddress, SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");
        final String key = key(remoteAddress);
        final CacheEntry e = getOrCreate(key);

        if (e.addUnsupported(protocol)) {
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
    static String key(Endpoint endpoint) {
        final String key;
        if (endpoint.isDomainSocket()) {
            try {
                key = URLDecoder.decode(endpoint.host(), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new Error(e);
            }
        } else {
            checkArgument(endpoint.hasPort(), "endpoint must have a port.");
            // It's okay to create the key using endpoint.host():
            // - If the endpoint has an IP address without host name, the IP address is used in the key to store
            //   and retrieve the value.
            // - If the endpoint has an IP address with host name, the host name is used in the key to store
            //   and retrieve the value.
            // - If the endpoint has host name only, the host name is used in the key to store and retrieve
            //   the value.
            key = key(endpoint.host(), endpoint.port());
        }
        return key;
    }

    @VisibleForTesting
    static String key(SocketAddress remoteAddress) {
        requireNonNull(remoteAddress, "remoteAddress");
        if (remoteAddress instanceof InetSocketAddress) {
            if (remoteAddress instanceof DomainSocketAddress) {
                return "unix:" + ((DomainSocketAddress) remoteAddress).path();
            } else {
                final InetSocketAddress raddr = (InetSocketAddress) remoteAddress;
                return key(raddr.getHostString(), raddr.getPort());
            }
        }

        if (remoteAddress instanceof io.netty.channel.unix.DomainSocketAddress) {
            return "unix:" + ((io.netty.channel.unix.DomainSocketAddress) remoteAddress).path();
        }

        throw new IllegalArgumentException("unsupported remote address: " + remoteAddress);
    }

    @VisibleForTesting
    static String key(String host, int port) {
        return new StringBuilder(host.length() + 6)
                .append(host)
                .append(':')
                .append(port)
                .toString();
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
        private volatile Set<SessionProtocol> unsupported = ImmutableSet.of();

        CacheEntry(@SuppressWarnings("unused") String key) {
            // Key is unused. It's just here to simplify the Map.computeIfAbsent() call in getOrCreate().
        }

        boolean addUnsupported(SessionProtocol protocol) {
            final Set<SessionProtocol> unsupported = this.unsupported;
            if (unsupported.contains(protocol)) {
                return false;
            }

            this.unsupported = ImmutableSet.<SessionProtocol>builder()
                                           .addAll(unsupported)
                                           .add(protocol)
                                           .build();
            return true;
        }

        boolean isUnsupported(SessionProtocol protocol) {
            requireNonNull(protocol, "protocol");
            return unsupported.contains(protocol);
        }

        @Override
        public String toString() {
            return unsupported.toString();
        }
    }

    private SessionProtocolNegotiationCache() {}
}
