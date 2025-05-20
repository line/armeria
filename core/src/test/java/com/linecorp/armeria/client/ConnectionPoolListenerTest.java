/*
 * Copyright 2025 LINE Corporation
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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.AttributeMap;

class ConnectionPoolListenerTest {

    @Test
    void andThen_shouldFinishWithoutErrors() throws Exception {

        final Integer[] counterArray = new Integer[6];

        final ConnectionPoolListener cpl1 = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counterArray[0] = 0;
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counterArray[3] = 30;
            }
        };

        final ConnectionPoolListener cpl2 = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counterArray[1] = 10;
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counterArray[4] = 40;
            }
        };

        final ConnectionPoolListener cpl3 = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counterArray[2] = 20;
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counterArray[5] = 50;
            }
        };

        final SessionProtocol protocol = SessionProtocol.HTTP;
        final InetSocketAddress remoteAddr = new InetSocketAddress("localhost", 8080);
        final InetSocketAddress localAddr = new InetSocketAddress("localhost", 9090);
        final AttributeMap attrs = mock(AttributeMap.class);

        final ConnectionPoolListener cplCombined = cpl1.andThen(cpl2).andThen(cpl3);

        cplCombined.connectionOpen(protocol, remoteAddr, localAddr, attrs);
        cplCombined.connectionClosed(protocol, remoteAddr, localAddr, attrs);
        cplCombined.close();

        assertThat(counterArray[0]).isEqualTo(0);
        assertThat(counterArray[1]).isEqualTo(10);
        assertThat(counterArray[2]).isEqualTo(20);
        assertThat(counterArray[3]).isEqualTo(30);
        assertThat(counterArray[4]).isEqualTo(40);
        assertThat(counterArray[5]).isEqualTo(50);
    }
}
