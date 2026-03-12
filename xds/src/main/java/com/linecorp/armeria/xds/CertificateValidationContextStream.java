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

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.CertificateUtil;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;

final class CertificateValidationContextStream extends RefCountedStream<CertificateValidationContextSnapshot> {

    private final CertificateValidationContext baseContext;
    private final SubscriptionContext context;
    private final SecretXdsResource xdsResource;

    CertificateValidationContextStream(SubscriptionContext context,
                                       SecretXdsResource xdsResource) {
        this(context, xdsResource, CertificateValidationContext.getDefaultInstance());
    }

    CertificateValidationContextStream(SubscriptionContext context,
                                       SecretXdsResource xdsResource,
                                       CertificateValidationContext baseContext) {
        this.context = context;
        this.xdsResource = xdsResource;
        this.baseContext = baseContext;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<CertificateValidationContextSnapshot> watcher) {
        final CertificateValidationContext validationContext =
                baseContext.toBuilder().mergeFrom(xdsResource.resource().getValidationContext()).build();

        final SnapshotStream<CertificateValidationContextSnapshot> stream =
                new DataSourceStream(validationContext.getTrustedCa(),
                                     validationContext.getWatchedDirectory(), context)
                        .map(bs -> {
                            if (!bs.isPresent()) {
                                return new CertificateValidationContextSnapshot(validationContext, null);
                            }
                            final List<X509Certificate> caCerts;
                            try (InputStream inputStream = bs.get().newInput()) {
                                caCerts = CertificateUtil.toX509Certificates(inputStream);
                            } catch (CertificateException | IOException e) {
                                return Exceptions.throwUnsafely(e);
                            }
                            return new CertificateValidationContextSnapshot(validationContext, caCerts);
                        });
        return stream.subscribe(watcher);
    }
}
