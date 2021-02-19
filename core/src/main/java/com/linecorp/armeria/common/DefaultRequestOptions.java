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

package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import io.netty.util.AttributeKey;

final class DefaultRequestOptions implements RequestOptions {

    private static final DefaultAttributeMap EMPTY_ATTRIBUTES = new DefaultAttributeMap(null);
    static final DefaultRequestOptions EMPTY = new DefaultRequestOptions(-1, EMPTY_ATTRIBUTES);

    private final long responseTimeoutMillis;
    private final DefaultAttributeMap attributeMap;

    static DefaultRequestOptions of(long responseTimeoutMillis, @Nullable DefaultAttributeMap attributeMap) {
        if (responseTimeoutMillis == -1 && attributeMap == null) {
            return EMPTY;
        } else {
            return new DefaultRequestOptions(responseTimeoutMillis,
                                             firstNonNull(attributeMap, EMPTY_ATTRIBUTES));
        }
    }

    private DefaultRequestOptions(long responseTimeoutMillis, DefaultAttributeMap attributeMap) {
        checkArgument(responseTimeoutMillis >= -1, "responseTimeoutMillis: %s (expected: >= -1)");
        requireNonNull(attributeMap, "attributeMap");

        this.responseTimeoutMillis = responseTimeoutMillis;
        this.attributeMap = attributeMap;
    }

    @Override
    public long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        if (attributeMap == EMPTY_ATTRIBUTES) {
            return Collections.emptyIterator();
        } else {
            return attributeMap.attrs();
        }
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
               attributeMap.equals(that.attributeMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(responseTimeoutMillis, attributeMap);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("responseTimeoutMillis", responseTimeoutMillis)
                          .add("attributeMap", attributeMap)
                          .toString();
    }
}
