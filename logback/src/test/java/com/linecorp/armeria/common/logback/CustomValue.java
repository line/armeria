package com.linecorp.armeria.common.logback;

public class CustomValue {

    final String value;

    public CustomValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "CustomValue(" + value + ')';
    }
}
