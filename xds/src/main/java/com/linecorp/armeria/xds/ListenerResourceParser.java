/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.google.protobuf.Message;

import io.envoyproxy.envoy.config.listener.v3.Listener;

final class ListenerResourceParser extends ResourceParser {

    static final ListenerResourceParser INSTANCE = new ListenerResourceParser();

    private ListenerResourceParser() {}

    @Override
    String name(Message message) {
        if (!(message instanceof Listener)) {
            throw new IllegalArgumentException("message not type of Listener");
        }
        return ((Listener) message).getName();
    }

    @Override
    Class<Listener> clazz() {
        return Listener.class;
    }

    @Override
    XdsType type() {
        return XdsType.LISTENER;
    }
}
