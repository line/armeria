/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.armeria.server.RoutingPredicate.parsePredicate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.server.RoutingPredicate.ParsedPredicate;

class RoutingPredicateTest {

    @Test
    void equal() {
        final ParsedPredicate p = parsePredicate("a=b b");
        assertThat(p.isNegated).isFalse();
        assertThat(p.name).isEqualTo("a");
        assertThat(p.value).isEqualTo("b b");
    }

    @Test
    void notEqual() {
        final ParsedPredicate p = parsePredicate("a!=b b");
        assertThat(p.isNegated).isTrue();
        assertThat(p.name).isEqualTo("a");
        assertThat(p.value).isEqualTo("b b");
    }

    @Test
    void contain() {
        final ParsedPredicate p = parsePredicate("a");
        assertThat(p.isNegated).isFalse();
        assertThat(p.name).isEqualTo("a");
        assertThat(p.value).isNull();
    }

    @Test
    void doesNotContain() {
        final ParsedPredicate p = parsePredicate("!a");
        assertThat(p.isNegated).isTrue();
        assertThat(p.name).isEqualTo("a");
        assertThat(p.value).isNull();
    }

    @Test
    void preserveSpacesInValue() {
        assertThat(parsePredicate("a=b")).isNotEqualTo(parsePredicate("a= b"));
        assertThat(parsePredicate("a= b")).isNotEqualTo(parsePredicate("a=b "));
        assertThat(parsePredicate("a!= b")).isNotEqualTo(parsePredicate("a!=b "));
    }

    @Test
    void notPreserveSpacesInName() {
        assertThat(parsePredicate("a=b")).isEqualTo(parsePredicate("a =b"));
        assertThat(parsePredicate(" a=b")).isEqualTo(parsePredicate("a =b"));
        assertThat(parsePredicate(" a!=b")).isEqualTo(parsePredicate("a !=b"));
        assertThat(parsePredicate(" !a")).isEqualTo(parsePredicate("!a "));
    }

    @Test
    void invalidPredicates() {
        assertThatThrownBy(() -> parsePredicate("!a=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parsePredicate("!!a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parsePredicate("!")).isInstanceOf(IllegalArgumentException.class);
    }
}
