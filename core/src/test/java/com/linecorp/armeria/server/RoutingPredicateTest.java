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

import static com.linecorp.armeria.server.RoutingPredicate.ofHeaders;
import static com.linecorp.armeria.server.RoutingPredicate.ofParams;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.QueryParams;

class RoutingPredicateTest {

    @Test
    void equal() {
        assertThat(parse("a=b b ").name()).isEqualTo("a_eq_b_b_");
    }

    @Test
    void notEqual() {
        assertThat(parse("a!= b b").name()).isEqualTo("a_ne__b_b");
    }

    @Test
    void contain() {
        assertThat(parse("a").name()).isEqualTo("a");
    }

    @Test
    void doesNotContain() {
        assertThat(parse("!a").name()).isEqualTo("not_a");
    }

    @Test
    void or() {
        assertThat(parse("b=2 || !a").name()).isEqualTo("b_eq_2_or_not_a");
    }

    @Test
    void preserveSpacesInValue() {
        assertThat(parse("a=b").name()).isNotEqualTo(parse("a= b").name());
        assertThat(parse("a= b").name()).isNotEqualTo(parse("a=b ").name());
        assertThat(parse("a!= b").name()).isNotEqualTo(parse("a!=b ").name());
        assertThat(parse(" a!= b || b= a").name()).isNotEqualTo(parse(" a!=b || b=a ").name());
    }

    @Test
    void notPreserveSpacesInName() {
        assertThat(parse("a=b").name()).isEqualTo(parse("a =b").name());
        assertThat(parse(" a=b").name()).isEqualTo(parse("a =b").name());
        assertThat(parse(" a!=b").name()).isEqualTo(parse("a !=b").name());
        assertThat(parse(" a!=b || b=a").name()).isEqualTo(parse(" a !=b ||b =a").name());
    }

    @Test
    void invalidPredicates() {
        assertThatThrownBy(() -> ofHeaders("!a=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofHeaders("!!a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofHeaders("!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!a=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!!a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!a ||| b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("a=3 ||| !b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("a ||| b=3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("a |||| b=3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!a ||")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!a || ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("a<5 || b<=4 ||")).isInstanceOf(IllegalArgumentException.class);
    }

    private static RoutingPredicate<QueryParams> parse(String expression) {
        return RoutingPredicate.of(expression, Function.identity(),
                                   name -> params -> true, (name, value) -> params -> true);
    }
}
