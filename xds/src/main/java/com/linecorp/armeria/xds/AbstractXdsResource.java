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

package com.linecorp.armeria.xds;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

abstract class AbstractXdsResource implements XdsResource {

    private final String version;
    private final long revision;

    AbstractXdsResource(String version, long revision) {
        this.version = version;
        this.revision = revision;
    }

    @Override
    public final String version() {
        return version;
    }

    @Override
    public final long revision() {
        return revision;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AbstractXdsResource that = (AbstractXdsResource) o;
        return revision == that.revision && Objects.equal(version, that.version) &&
               resource().equals(that.resource());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(version, revision, resource());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("type", type().name())
                          .add("version", version())
                          .add("revision", revision())
                          .toString();
    }
}
