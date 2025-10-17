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

package com.linecorp.armeria.internal.common.util;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Defines the type of name to use for a self-signed certificate.
 * This is used to determine whether to use the Common Name (CN) or the Subject Alternative Name (SAN)
 * for the certificate's identity.
 */
@UnstableApi
public enum SelfSingedCertificateNameType {
    /**
     * Use the Common Name (CN) for the self-signed certificate.
     */
    COMMON_NAME,
    /**
     * Use the Subject Alternative Name (SAN) for the self-signed certificate.
     */
    SUBJECT_ALTERNATIVE_NAME
}
