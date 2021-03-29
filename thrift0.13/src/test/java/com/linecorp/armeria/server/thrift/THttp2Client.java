package com.linecorp.armeria.server.thrift;

import org.apache.thrift.transport.TTransportException;

import io.netty.handler.codec.http.HttpHeaders;

final class THttp2Client extends AbstractTHttp2Client {

    THttp2Client(String uriStr, HttpHeaders defaultHeaders) throws TTransportException {
        super(uriStr, defaultHeaders);
    }
}
