package com.linecorp.armeria.spring.tomcat.demo;

public class GlobalBaseException extends RuntimeException {
    GlobalBaseException(String message) {
        super(message);
    }
}
