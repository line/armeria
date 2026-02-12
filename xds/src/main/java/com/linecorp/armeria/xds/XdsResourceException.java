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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An exception that indicates an error occurred while processing an xDS resource.
 */
@UnstableApi
public class XdsResourceException extends RuntimeException {

    private static final long serialVersionUID = -537173900451481714L;

    private final XdsType type;
    private final String name;

    XdsResourceException(XdsType type, String name) {
        this(type, name, (Throwable) null);
    }

    XdsResourceException(XdsType type, String name, String message) {
        this(type, name, new IllegalArgumentException(message));
    }

    XdsResourceException(XdsType type, String name, @Nullable Throwable t) {
        super(t);
        this.type = type;
        this.name = name;
    }

    XdsResourceException(XdsType type, String name, @Nullable Throwable t, String message) {
        super(message, t);
        this.type = type;
        this.name = name;
    }

    /**
     * Returns the {@link XdsType} of the resource that caused the error.
     */
    public XdsType type() {
        return type;
    }

    /**
     * Returns the name of the resource that caused the error.
     */
    public String name() {
        return name;
    }

    static XdsResourceException maybeWrap(XdsType type, String name, Throwable t) {
        if (t instanceof XdsResourceException) {
            return (XdsResourceException) t;
        }
        return new XdsResourceException(type, name, t);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("type", type)
                          .add("name", name)
                          .add("cause", getCause())
                          .toString();
    }
}
