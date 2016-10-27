package com.linecorp.armeria.common.logback;

import java.util.function.Function;

public final class CustomValueStringifier implements Function<CustomValue, String> {
    @Override
    public String apply(CustomValue o) {
        return o.value;
    }
}
