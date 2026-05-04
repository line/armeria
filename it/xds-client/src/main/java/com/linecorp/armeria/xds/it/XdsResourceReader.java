/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.it;

import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.protobuf.GeneratedMessageV3;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * Delegates to {@link com.linecorp.armeria.xds.XdsResourceReader} for YAML/JSON parsing.
 * Keeps test-only utilities like {@link #escapeMultiLine(String)}.
 */
public final class XdsResourceReader {

    public static Bootstrap fromYaml(String yaml) {
        return com.linecorp.armeria.xds.XdsResourceReader.from(yaml, Bootstrap.class);
    }

    public static <T extends GeneratedMessageV3> T fromYaml(String yaml, Class<T> clazz) {
        return com.linecorp.armeria.xds.XdsResourceReader.from(yaml, clazz);
    }

    public static <T extends GeneratedMessageV3> T fromJson(String json, Class<T> clazz) {
        return com.linecorp.armeria.xds.XdsResourceReader.from(json, clazz);
    }

    private static final Escaper multiLineEscaper = Escapers.builder()
                                                            .addEscape('\\', "\\\\")
                                                            .addEscape('"', "\\\"")
                                                            .addEscape('\n', "\\n")
                                                            .addEscape('\r', "\\r")
                                                            .build();

    static String escapeMultiLine(String str) {
        return multiLineEscaper.escape(str);
    }

    private XdsResourceReader() {}
}
