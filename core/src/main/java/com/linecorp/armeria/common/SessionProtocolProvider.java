/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;

/**
 * Registers the {@link SessionProtocol}s dynamically via Java SPI (Service Provider Interface).
 */
public abstract class SessionProtocolProvider {

    /**
     * Returns the {@link Entry}s to register as {@link SessionProtocol}s.
     */
    protected abstract Set<Entry> entries();

    /**
     * A registration entry of a {@link SessionProtocol}.
     */
    protected static final class Entry implements Comparable<Entry> {
        final String uriText;
        final boolean useTls;
        final boolean isMultiplex;
        final int defaultPort;

        /**
         * Creates a new instance.
         */
        public Entry(String uriText, boolean useTls, boolean isMultiplex, int defaultPort) {
            requireNonNull(uriText, "uriText");
            checkArgument(defaultPort > 0 && defaultPort < 65536,
                          "defaultPort: %s (expected: 0-65535)", defaultPort);

            this.uriText = Ascii.toLowerCase(uriText);
            this.useTls = useTls;
            this.isMultiplex = isMultiplex;
            this.defaultPort = defaultPort;


        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj == null || obj.getClass() != Entry.class) {
                return false;
            }

            return uriText.equals(((Entry) obj).uriText);
        }

        @Override
        public int compareTo(Entry o) {
            return uriText.compareTo(o.uriText);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("uriText", uriText)
                              .add("useTls", useTls)
                              .add("isMultiplex", isMultiplex)
                              .add("defaultPort", defaultPort).toString();
        }

        @Override
        public int hashCode() {
            return uriText.hashCode();
        }
    }
}
