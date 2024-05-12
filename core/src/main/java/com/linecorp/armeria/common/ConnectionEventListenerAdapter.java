package com.linecorp.armeria.common;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeMap;

public class ConnectionEventListenerAdapter implements ConnectionEventListener {
    static final ConnectionEventListenerAdapter NOOP = new ConnectionEventListenerAdapter();

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol,
                                 SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {}

    @Override
    public void connectionActive(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {}

    @Override
    public void connectionIdle(SessionProtocol protocol,
                               InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress,
                               AttributeMap attrs) throws Exception {}

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress,
                                 boolean isIdle,
                                 AttributeMap attrs) throws Exception {}
}
