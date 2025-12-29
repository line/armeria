/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

final class SecretResourceParser extends ResourceParser<Secret, SecretXdsResource> {

    static final SecretResourceParser INSTANCE = new SecretResourceParser();

    @Override
    String name(Secret message) {
        return message.getName();
    }

    @Override
    Class<Secret> clazz() {
        return Secret.class;
    }

    @Override
    SecretXdsResource parse(Secret message, String version, long revision) {
        return new SecretXdsResource(message, version, revision);
    }

    @Override
    boolean isFullStateOfTheWorld() {
        return false;
    }

    @Override
    XdsType type() {
        return XdsType.SECRET;
    }
}
