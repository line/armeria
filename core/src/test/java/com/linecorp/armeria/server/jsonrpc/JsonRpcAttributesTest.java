/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.server.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.netty.util.AttributeKey;

class JsonRpcAttributesTest {

    @Test
    void attributeKeys_properties() {
        final AttributeKey<Object> idKey = JsonRpcAttributes.ID;
        final AttributeKey<String> methodKey = JsonRpcAttributes.METHOD;
        final AttributeKey<Boolean> isNotificationKey = JsonRpcAttributes.IS_NOTIFICATION;

        assertThat(idKey.name()).contains("ID");
        assertThat(methodKey.name()).contains("METHOD");
        assertThat(isNotificationKey.name()).contains("IS_NOTIFICATION");
    }
}
