/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.tomcat;

import java.util.Queue;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.ProtocolHandler;

import com.linecorp.armeria.common.HttpData;

/**
 * A {@link TomcatHandler} for tomcat-8.0.
 */
final class Tomcat80Handler implements TomcatHandler {
    @Override
    public InputBuffer inputBuffer(HttpData content) {
        return new Tomcat80InputBuffer(content);
    }

    @Override
    public OutputBuffer outputBuffer(Queue<HttpData> data) {
        return new Tomcat80OutputBuffer(data);
    }

    @Override
    public Class<? extends ProtocolHandler> protocolHandlerClass() {
        return Tomcat80ProtocolHandler.class;
    }
}
