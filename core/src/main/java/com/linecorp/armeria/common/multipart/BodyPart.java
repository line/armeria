/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A body part entity.
 */
public interface BodyPart {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/main/java/io/helidon/media/multipart/BodyPart.java

    /**
     * Returns a new {@link BodyPartBuilder}.
     */
    static BodyPartBuilder builder() {
        return new BodyPartBuilder();
    }

    /**
     * Returns HTTP part headers.
     */
    HttpHeaders headers();

    /**
     * Returns the reactive representation of the part content.
     */
    StreamMessage<HttpData> content();

    /**
     * Returns the control name.
     *
     * @return the {@code name} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String name() {
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.name();
        } else {
            return null;
        }
    }

    /**
     * Returns the file name.
     *
     * @return the {@code filename} parameter of the {@code "content-disposition"}
     *         header, or {@code null} if not present.
     */
    @Nullable
    default String filename() {
        final ContentDisposition contentDisposition = headers().contentDisposition();
        if (contentDisposition != null) {
            return contentDisposition.filename();
        } else {
            return null;
        }
    }
}
