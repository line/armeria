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

package com.linecorp.armeria.common;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Verifies TLS peer certificates during handshake.
 */
@UnstableApi
public interface TlsPeerVerifier {

    /**
     * Verifies the peer's certificate chain. One may throw an exception if a peer is not valid.
     */
    void verify(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException;
}
