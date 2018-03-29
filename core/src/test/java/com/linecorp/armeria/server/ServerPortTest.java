/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class ServerPortTest {
    @Test
    public void allowedProtocols() {
        new ServerPort(0, HTTP);
        new ServerPort(0, HTTPS);
        new ServerPort(0, PROXY, HTTP);
        new ServerPort(0, PROXY, HTTPS);
        new ServerPort(0, PROXY, HTTP, HTTPS);
    }

    @Test
    public void disallowedProtocols() {
        assertThatThrownBy(() -> new ServerPort(0, PROXY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerPort(0, H1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerPort(0, H1C)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerPort(0, H2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerPort(0, H2C)).isInstanceOf(IllegalArgumentException.class);
    }
}
