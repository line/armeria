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

/**
 * MIME Parsing exception.
 */
public final class MimeParsingException extends RuntimeException {

    // Forked from https://github.com/oracle/helidon/blob/a9363a3d226a3154e2fb99abe230239758504436/media/multipart/src/main/java/io/helidon/media/multipart/MimeParser.java

    private static final long serialVersionUID = 5242709471451591960L;

    /**
     * Creates a new exception.
     */
    public MimeParsingException() {}

    /**
     * Creates a new exception with the specified {@code message}.
     */
    public MimeParsingException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the specified {@link Throwable}.
     */
    public MimeParsingException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new exception with the specified {@code message} and {@link Throwable}.
     */
    public MimeParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
