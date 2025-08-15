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

package com.linecorp.armeria.internal.common.thrift.logging;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TIOStreamTransport;

final class TMaskingSerializer {

    private final TProtocolFactory protocolFactory;
    private final TBaseSelectorCache selectorCache;

    TMaskingSerializer(TProtocolFactory protocolFactory, TBaseSelectorCache selectorCache) {
        this.protocolFactory = protocolFactory;
        this.selectorCache = selectorCache;
    }

    byte[] serialize(TBase<?, ?> base) throws TException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        final TProtocol protocol = protocolFactory.getProtocol(new TIOStreamTransport(baos));
        final MaskingTProtocol tProtocol = new MaskingTProtocol(protocol, base, selectorCache);
        base.write(tProtocol);
        return baos.toByteArray();
    }

    String toString(TBase<?, ?> base) throws TException {
        return new String(serialize(base), StandardCharsets.UTF_8);
    }
}
