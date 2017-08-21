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

package com.linecorp.armeria.common.metric;

import static java.util.Comparator.comparing;

import java.util.Iterator;

import com.google.common.collect.Streams;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.NamingConvention;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;

/**
 * {@link MeterRegistry} implementation for <a href="http://metrics.dropwizard.io/">Dropwizard Metrics</a>.
 * This implementation adds more convenient constructors and sets more sensible default {@link NamingConvention}
 * and {@link HierarchicalNameMapper} on top of the upstream implementation.
 */
@SuppressWarnings("ClassNameSameAsAncestorName")
public class DropwizardMeterRegistry extends io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry {

    private static final HierarchicalNameMapper DEFAULT_NAME_MAPPER = (name, tags) -> {
        final Iterator<Tag> i = tags.iterator();
        if (!i.hasNext()) {
            return name;
        }

        // <name>.<tagName>:<tagValue>.<tagName>:<tagValue>...
        // e.g. armeria.server.requests.method:greet.service:HelloService
        final StringBuilder buf = new StringBuilder();
        buf.append(name);
        Streams.stream(i).sorted(comparing(Tag::getKey)).forEach(tag -> {
            buf.append('.').append(tag.getKey());
            buf.append(':').append(tag.getValue());
        });

        return buf.toString();
    };

    /**
     * Creates a new instance with the default {@link HierarchicalNameMapper}..
     */
    public DropwizardMeterRegistry() {
        this(DEFAULT_NAME_MAPPER);
    }

    /**
     * Creates a new instance with the specified {@link HierarchicalNameMapper}.
     */
    public DropwizardMeterRegistry(HierarchicalNameMapper nameMapper) {
        this(nameMapper, Clock.SYSTEM);
    }

    /**
     * Creates a new instance with the specified {@link HierarchicalNameMapper}.
     */
    public DropwizardMeterRegistry(HierarchicalNameMapper nameMapper, Clock clock) {
        super(nameMapper, clock);
        config().namingConvention(MoreNamingConventions.dropwizard());
    }
}
