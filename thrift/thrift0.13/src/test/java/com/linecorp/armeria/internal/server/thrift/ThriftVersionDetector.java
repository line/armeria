/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.util.Version;

public final class ThriftVersionDetector {

    private static final int majorVersion;
    private static final int minorVersion;

    static {
        final Pattern pattern = Pattern.compile("^armeria-thrift(?<major>\\d+)\\.(?<minor>\\d+)$");
        final Entry<Integer, Integer> thriftVersionEntry =
                Version.getAll().keySet().stream()
                       .map(name -> {
                           final Matcher matcher = pattern.matcher(name);
                           if (!matcher.matches()) {
                               return null;
                           }
                           final int major = Integer.valueOf(matcher.group("major"));
                           final int minor = Integer.valueOf(matcher.group("minor"));
                           return new SimpleEntry<>(major, minor);
                       })
                       .filter(Objects::nonNull)
                       .findFirst()
                       .orElseThrow(() -> new RuntimeException("Couldn't find a thrift version"));
        majorVersion = thriftVersionEntry.getKey();
        minorVersion = thriftVersionEntry.getValue();
    }

    public static int majorVersion() {
        return majorVersion;
    }

    public static int minorVersion() {
        return minorVersion;
    }

    private ThriftVersionDetector() {}
}
