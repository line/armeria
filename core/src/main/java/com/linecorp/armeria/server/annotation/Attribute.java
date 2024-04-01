package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.internal.server.annotation.DefaultValues;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Attribute {

    String value();
    Class<?> prefix() default DefaultValues.class;
}
