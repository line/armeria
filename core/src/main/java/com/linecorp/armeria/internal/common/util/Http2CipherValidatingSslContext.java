/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import java.util.Set;

import javax.net.ssl.SSLEngine;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.util.Exceptions;

import io.netty.handler.ssl.DelegatingSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * A {@link DelegatingSslContext} that validates HTTP/2 cipher requirements on {@link SSLEngine} creation.
 * This avoids allocating a dedicated, temporary {@link SSLEngine} during SSL context construction.
 */
class Http2CipherValidatingSslContext extends DelegatingSslContext {

    static SslContext of(SslContext delegate, boolean tlsAllowUnsafeCiphers) {
        if (delegate instanceof ReferenceCounted) {
            return new ReferenceCountedHttp2CipherValidatingSslContext(delegate, tlsAllowUnsafeCiphers);
        }
        return new Http2CipherValidatingSslContext(delegate, tlsAllowUnsafeCiphers);
    }

    private final Set<String> ciphers;
    private final boolean tlsAllowUnsafeCiphers;
    private volatile boolean validated;

    Http2CipherValidatingSslContext(SslContext delegate, boolean tlsAllowUnsafeCiphers) {
        super(delegate);
        ciphers = ImmutableSet.copyOf(delegate.cipherSuites());
        this.tlsAllowUnsafeCiphers = tlsAllowUnsafeCiphers;
    }

    @Override
    protected void initEngine(SSLEngine engine) {
        if (!validated) {
            validate(engine);
            // Mark validated only after a successful check so that a failure re-executes
            // on subsequent engine creations and keeps throwing.
            validated = true;
        }
    }

    private void validate(SSLEngine engine) {
        try {
            final Set<String> enabledProtocols = ImmutableSet.copyOf(engine.getEnabledProtocols());
            SslContextUtil.validateHttp2Ciphers(ciphers, tlsAllowUnsafeCiphers, enabledProtocols);
        } catch (Throwable t) {
            // Netty does not release newly created engines if initEngine throws, which would
            // leak native memory for reference-counted engines (such as OpenSSL engines).
            ReferenceCountUtil.safeRelease(engine);
            Exceptions.throwUnsafely(t);
        }
    }

    /**
     * An {@link Http2CipherValidatingSslContext} that forwards {@link ReferenceCounted} operations
     * to the underlying reference-counted delegate (e.g. ReferenceCountedOpenSslContext).
     */
    private static final class ReferenceCountedHttp2CipherValidatingSslContext
            extends Http2CipherValidatingSslContext implements ReferenceCounted {

        private final ReferenceCounted refCnt;

        ReferenceCountedHttp2CipherValidatingSslContext(SslContext delegate,
                                                        boolean tlsAllowUnsafeCiphers) {
            super(delegate, tlsAllowUnsafeCiphers);
            refCnt = (ReferenceCounted) delegate;
        }

        @Override
        public int refCnt() {
            return refCnt.refCnt();
        }

        @Override
        public ReferenceCounted retain() {
            refCnt.retain();
            return this;
        }

        @Override
        public ReferenceCounted retain(int increment) {
            refCnt.retain(increment);
            return this;
        }

        @Override
        public ReferenceCounted touch() {
            refCnt.touch();
            return this;
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            refCnt.touch(hint);
            return this;
        }

        @Override
        public boolean release() {
            return refCnt.release();
        }

        @Override
        public boolean release(int decrement) {
            return refCnt.release(decrement);
        }
    }
}
