package com.linecorp.armeria.internal.common.thrift;

import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.TTransportException;

import io.netty.buffer.ByteBuf;

public final class TByteBufTransport extends AbstractTByteBufTransport {

    public TByteBufTransport(ByteBuf buf) {
        super(buf);
    }

    @Override
    public TConfiguration getConfiguration() {
        return TConfiguration.DEFAULT;
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
