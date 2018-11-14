/*
 * Copyright 2015 LINE Corporation
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

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.AttributeMap;

/**
 * Helper Handler delegate event to decorated  {@link ConnectionPoolListener}.
 * Ignore Exception when created {@link ConnectionPoolListener} throw Exception.s
 */
final class SafeConnectionPoolListener implements ConnectionPoolListener {
    private static final Logger logger = LoggerFactory.getLogger(SafeConnectionPoolListener.class);

    private final ConnectionPoolListener handler;

    SafeConnectionPoolListener(ConnectionPoolListener handler) {
        this.handler = handler;
    }

    @Override
    public void connectionOpen(SessionProtocol protocol,
                               InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr,
                               AttributeMap attrs) {
        try {
            handler.connectionOpen(protocol, remoteAddr, localAddr, attrs);
        } catch (Exception e) {
            logFailure("connectionOpen", e);
        }
    }

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr,
                                 AttributeMap attrs) {
        try {
            handler.connectionClosed(protocol, remoteAddr, localAddr, attrs);
        } catch (Exception e) {
            logFailure("connectionClosed", e);
        }
    }

    private void logFailure(String handlerName, Throwable cause) {
        if (logger.isWarnEnabled()) {
            logger.warn("Exception handling {}.{}()",
                        handler.getClass().getName(), handlerName, cause);
        }
    }
}
