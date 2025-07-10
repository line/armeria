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

import java.nio.charset.StandardCharsets;

import org.apache.thrift.TBase;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TMemoryInputTransport;

final class TMaskingDeserializer extends TDeserializer {

    private final TProtocolFactory protocolFactory;
    private final TBaseSelectorCache selectorCache;

    TMaskingDeserializer(TProtocolFactory protocolFactory,
                         TBaseSelectorCache selectorCache) throws TException {
        this.protocolFactory = protocolFactory;
        this.selectorCache = selectorCache;
    }

    @Override
    public void deserialize(TBase base, byte[] bytes) throws TException {
        final TMemoryInputTransport transport = new TMemoryInputTransport();
        transport.reset(bytes, 0, bytes.length);
        try {
            final TProtocol protocol = protocolFactory.getProtocol(transport);
            final UnMaskingTProtocol tProtocol = new UnMaskingTProtocol(protocol, base, selectorCache);
            base.read(tProtocol);
        } finally {
            transport.clear();
        }
    }

    @Override
    public void fromString(TBase base, String data) throws TException {
        deserialize(base, data.getBytes(StandardCharsets.UTF_8));
    }
}
