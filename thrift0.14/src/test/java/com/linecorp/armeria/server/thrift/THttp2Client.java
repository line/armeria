package com.linecorp.armeria.server.thrift;

import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.TTransportException;

import io.netty.handler.codec.http.HttpHeaders;

final class THttp2Client extends AbstractTHttp2Client {

    THttp2Client(String uriStr, HttpHeaders defaultHeaders) throws TTransportException {
        super(uriStr, defaultHeaders);
    }

    @Override
    public TConfiguration getConfiguration() {
        return delegate().getConfiguration();
    }

    @Override
    public void updateKnownMessageSize(long size) throws TTransportException {
        delegate().updateKnownMessageSize(size);
    }

    @Override
    public void checkReadBytesAvailable(long numBytes) throws TTransportException {
        delegate().checkReadBytesAvailable(numBytes);
    }
}
