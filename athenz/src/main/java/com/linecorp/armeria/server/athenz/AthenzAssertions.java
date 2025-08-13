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

package com.linecorp.armeria.server.athenz;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.yahoo.rdl.Struct;

final class AthenzAssertions {

    private final Map<String, List<Struct>> roleStandardAllowMap = new HashMap<>();
    private final Map<String, List<Struct>> roleWildcardAllowMap = new HashMap<>();
    private final Map<String, List<Struct>> roleStandardDenyMap = new HashMap<>();
    private final Map<String, List<Struct>> roleWildcardDenyMap = new HashMap<>();

    Map<String, List<Struct>> roleStandardAllowMap() {
        return roleStandardAllowMap;
    }

    Map<String, List<Struct>> roleWildcardAllowMap() {
        return roleWildcardAllowMap;
    }

    Map<String, List<Struct>> roleStandardDenyMap() {
        return roleStandardDenyMap;
    }

    Map<String, List<Struct>> roleWildcardDenyMap() {
        return roleWildcardDenyMap;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AthenzAssertions)) {
            return false;
        }
        final AthenzAssertions that = (AthenzAssertions) o;
        return roleStandardAllowMap.equals(that.roleStandardAllowMap) &&
               roleWildcardAllowMap.equals(that.roleWildcardAllowMap) &&
               roleStandardDenyMap.equals(that.roleStandardDenyMap) &&
               roleWildcardDenyMap.equals(that.roleWildcardDenyMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleStandardAllowMap, roleWildcardAllowMap, roleStandardDenyMap,
                            roleWildcardDenyMap);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("roleStandardAllowMap", roleStandardAllowMap)
                          .add("roleWildcardAllowMap", roleWildcardAllowMap)
                          .add("roleStandardDenyMap", roleStandardDenyMap)
                          .add("roleWildcardDenyMap", roleWildcardDenyMap)
                          .toString();
    }
}
