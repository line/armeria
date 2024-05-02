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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Specifies when to remove the temporary files created for multipart requests.
 */
@UnstableApi
public enum MultipartRemovalStrategy {
    /**
     * Never remove the temporary files.
     *
     * <p><strong>Warning:</strong> This option may cause a disk space leak if the temporary files are not
     * removed manually.
     */
    NEVER,
    /**
     * Remove the temporary files after the response is fully sent.
     */
    ON_RESPONSE_COMPLETION
}
