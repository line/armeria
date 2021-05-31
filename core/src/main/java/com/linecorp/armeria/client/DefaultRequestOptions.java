/*
 * Copyright 2021 LINE Corporation
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

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import io.netty.util.AttributeKey;

final class DefaultRequestOptions implements RequestOptions {

    static final DefaultRequestOptions EMPTY = new DefaultRequestOptions(-1, -1,
                                                                         -1, ImmutableMap.of());

    private final long responseTimeoutMillis;
    private final long writeTimeoutMillis;
    private final long maxResponseLength;
    private final Map<AttributeKey<?>, Object> attributeMap;

    DefaultRequestOptions(long responseTimeoutMillis, long writeTimeoutMillis,
                          long maxResponseLength, Map<AttributeKey<?>, Object> attributeMap) {
        this.responseTimeoutMillis = responseTimeoutMillis;
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.maxResponseLength = maxResponseLength;
        this.attributeMap = attributeMap;
    }

    @Override
    public long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    @Override
    public long writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    @Override
    public long maxResponseLength() {
        return maxResponseLength;
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        return attributeMap.entrySet().iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof DefaultRequestOptions)) {
            return false;
        }

        final DefaultRequestOptions that = (DefaultRequestOptions) o;

        return responseTimeoutMillis == that.responseTimeoutMillis &&
               writeTimeoutMillis == that.writeTimeoutMillis &&
               maxResponseLength == that.maxResponseLength &&
               attributeMap.equals(that.attributeMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(responseTimeoutMillis, writeTimeoutMillis, maxResponseLength, attributeMap);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("responseTimeoutMillis", responseTimeoutMillis)
                          .add("writeTimeoutMillis", writeTimeoutMillis)
                          .add("maxResponseLength", maxResponseLength)
                          .add("attributeMap", attributeMap)
                          .toString();
    }
}
