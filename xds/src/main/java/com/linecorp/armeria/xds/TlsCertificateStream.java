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

import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.TlsKeyPair;

import io.envoyproxy.envoy.config.core.v3.WatchedDirectory;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;

final class TlsCertificateStream extends RefCountedStream<TlsCertificateSnapshot> {

    private final SubscriptionContext context;
    private final SecretXdsResource resource;

    TlsCertificateStream(SubscriptionContext context, SecretXdsResource resource) {
        this.context = context;
        this.resource = resource;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<TlsCertificateSnapshot> watcher) {
        final TlsCertificate tlsCertificate = resource.resource().getTlsCertificate();
        final ImmutableList.Builder<DataSourceStream> childBuilders =  ImmutableList.builder();
        final DataSourceStream privateKeyWatcher =
                new DataSourceStream(tlsCertificate.getPrivateKey(),
                                     tlsCertificate.getWatchedDirectory(), context);
        childBuilders.add(privateKeyWatcher);
        final DataSourceStream certChainWatcher =
                new DataSourceStream(tlsCertificate.getCertificateChain(),
                                     tlsCertificate.getWatchedDirectory(), context);
        childBuilders.add(certChainWatcher);
        final DataSourceStream passwordWatcher =
                new DataSourceStream(tlsCertificate.getPassword(),
                                     WatchedDirectory.getDefaultInstance(), context);
        childBuilders.add(passwordWatcher);
        final SnapshotStream<TlsCertificateSnapshot> stream =
                SnapshotStream.combineNLatest(childBuilders.build())
                              .map(combined -> {
                                  final Optional<ByteString> privKeyBytes = combined.get(0);
                                  final Optional<ByteString> certChainBytes = combined.get(1);
                                  final TlsKeyPair tlsKeyPair;
                                  if (privKeyBytes.isPresent() && certChainBytes.isPresent()) {
                                      final String password =
                                              combined.get(2).map(ByteString::toStringUtf8).orElse(null);
                                      try {
                                          tlsKeyPair = TlsKeyPair.of(privKeyBytes.get().newInput(), password,
                                                                     certChainBytes.get().newInput());
                                      } catch (Exception e) {
                                          throw new XdsResourceException(XdsType.SECRET, resource.name(), e);
                                      }
                                  } else {
                                      tlsKeyPair = null;
                                  }
                                  return new TlsCertificateSnapshot(tlsCertificate, tlsKeyPair);
                              });
        return stream.subscribe(watcher);
    }
}
