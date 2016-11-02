package com.linecorp.armeria.client.endpoint.zookeeper;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class DefaultNodeValueConverterTest {
    @Rule
    public ExpectedException exceptionGrabber = ExpectedException.none();

    @Test
    public void convert() {
        NodeValueConverter converter = new DefaultNodeValueConverter();
        converter.convert("localhost:8001,localhost:8002:2,192.abc.1.2".getBytes());
        assertThat(
                converter.convert("localhost:8001,localhost:8002:2,192.168.1.2".getBytes()),
                is(ImmutableList.of(Endpoint.of("localhost", 8001), Endpoint.of("localhost", 8002, 2),
                                    Endpoint.of("192.168.1.2"))));
        exceptionGrabber.expect(IllegalArgumentException.class);
        converter.convert("http://localhost:8001,localhost:8002:2,192.168.1.2".getBytes());
    }

}
