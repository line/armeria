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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.GenericSecret;

/**
 * A snapshot of a {@link GenericSecret} resource with its resolved credential value.
 * This snapshot is created when resolving generic secret configuration from xDS resources,
 * analogous to {@link TlsCertificateSnapshot} for TLS certificates.
 */
@UnstableApi
public final class GenericSecretSnapshot implements Snapshot<GenericSecret> {

    private final GenericSecret resource;
    @Nullable
    private final String credential;

    GenericSecretSnapshot(GenericSecret resource, @Nullable String credential) {
        this.resource = resource;
        this.credential = credential;
    }

    /**
     * Returns the resolved credential value, or {@code null} if the credential
     * is not available or empty.
     */
    @Nullable
    public String credential() {
        return credential;
    }

    @Override
    public GenericSecret xdsResource() {
        return resource;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final GenericSecretSnapshot that = (GenericSecretSnapshot) object;
        return Objects.equal(resource, that.resource);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(resource);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .toString();
    }

    @Override
    public String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .add("genericSecret", resource)
                          .toString();
    }
}
