/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import java.util.function.Predicate;

import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A utility class which helps a user to easily handle a SAML message.
 */
public final class SamlUtil {

    /**
     * Returns a {@link NameID} that its name format equals to the specified {@code expectedFormat},
     * from the {@link Response}.
     */
    @Nullable
    public static NameID getNameId(Response response, SamlNameIdFormat expectedFormat) {
        return getNameId(response, nameId -> nameId.getFormat().equals(expectedFormat.urn()));
    }

    /**
     * Returns a {@link NameID} which is matched to the specified {@code filter} from the {@link Response}.
     */
    @Nullable
    public static NameID getNameId(Response response, Predicate<NameID> filter) {
        return response.getAssertions().stream()
                       .map(s -> s.getSubject().getNameID())
                       .filter(filter)
                       .findFirst().orElse(null);
    }

    private SamlUtil() {}
}
