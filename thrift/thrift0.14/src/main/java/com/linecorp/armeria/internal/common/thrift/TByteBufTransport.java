/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.common.thrift;

import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.TTransportException;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;

public final class TByteBufTransport extends AbstractTByteBufTransport {

    public TByteBufTransport(ByteBuf buf) {
        super(buf);
    }

    @Nullable
    @Override
    public TConfiguration getConfiguration() {
        return null;
    }

    @Override
    public void updateKnownMessageSize(long size) throws TTransportException {
        // This method is not called by the 'TProtocol's provided by Armeria
    }

    @Override
    public void checkReadBytesAvailable(long numBytes) throws TTransportException {
        // The size of readable bytes is already checked by the underlying ByteBuf.
    }
}
