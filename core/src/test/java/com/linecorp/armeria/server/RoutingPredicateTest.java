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

import static com.linecorp.armeria.server.RoutingPredicate.CONTAIN_PATTERN;
import static com.linecorp.armeria.server.RoutingPredicate.ParsedComparingPredicate.parse;
import static com.linecorp.armeria.server.RoutingPredicate.ofHeaders;
import static com.linecorp.armeria.server.RoutingPredicate.ofParams;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.server.RoutingPredicate.ParsedComparingPredicate;

class RoutingPredicateTest {

    @Test
    void equal() {
        final ParsedComparingPredicate p = parse("a=b b");
        assertThat(p.name).isEqualTo("a");
        assertThat(p.comparator).isEqualTo("=");
        assertThat(p.value).isEqualTo("b b");
    }

    @Test
    void notEqual() {
        final ParsedComparingPredicate p = parse("a!=b b");
        assertThat(p.name).isEqualTo("a");
        assertThat(p.comparator).isEqualTo("!=");
        assertThat(p.value).isEqualTo("b b");
    }

    @Test
    void greaterThan() {
        final ParsedComparingPredicate p = parse("a>1");
        assertThat(p.name).isEqualTo("a");
        assertThat(p.comparator).isEqualTo(">");
        assertThat(p.value).isEqualTo("1");
    }

    @Test
    void greaterThanOrEquals() {
        final ParsedComparingPredicate p = parse("a>=1");
        assertThat(p.name).isEqualTo("a");
        assertThat(p.comparator).isEqualTo(">=");
        assertThat(p.value).isEqualTo("1");
    }

    @Test
    void lessThan() {
        final ParsedComparingPredicate p = parse("a<1");
        assertThat(p.name).isEqualTo("a");
        assertThat(p.comparator).isEqualTo("<");
        assertThat(p.value).isEqualTo("1");
    }

    @Test
    void lessThanOrEquals() {
        final ParsedComparingPredicate p = parse("a<=1");
        assertThat(p.name).isEqualTo("a");
        assertThat(p.comparator).isEqualTo("<=");
        assertThat(p.value).isEqualTo("1");
    }

    @Test
    void contain() {
        final Matcher m = CONTAIN_PATTERN.matcher("a");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("");
        assertThat(m.group(2)).isEqualTo("a");
    }

    @Test
    void doesNotContain() {
        final Matcher m = CONTAIN_PATTERN.matcher("!a");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("!");
        assertThat(m.group(2)).isEqualTo("a");
    }

    @Test
    void preserveSpacesInValue() {
        assertThat(parse("a=b")).isNotEqualTo(parse("a= b"));
        assertThat(parse("a= b")).isNotEqualTo(parse("a=b "));
        assertThat(parse("a!= b")).isNotEqualTo(parse("a!=b "));
    }

    @Test
    void notPreserveSpacesInName() {
        assertThat(parse("a=b")).isEqualTo(parse("a =b"));
        assertThat(parse(" a=b")).isEqualTo(parse("a =b"));
        assertThat(parse(" a!=b")).isEqualTo(parse("a !=b"));
    }

    @Test
    void invalidPredicates() {
        assertThatThrownBy(() -> ofHeaders("!a=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofHeaders("!!a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofHeaders("!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!a=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!!a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ofParams("!")).isInstanceOf(IllegalArgumentException.class);
    }
}
