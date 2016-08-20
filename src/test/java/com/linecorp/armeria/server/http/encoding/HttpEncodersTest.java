package com.linecorp.armeria.server.http.encoding;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;

public class HttpEncodersTest {

    @Rule public MockitoRule mocks = MockitoJUnit.rule();

    @Mock private HttpRequest request;

    @Test
    public void noAcceptEncoding() {
        when(request.headers()).thenReturn(HttpHeaders.EMPTY_HEADERS);
        assertThat(HttpEncoders.getWrapperForRequest(request)).isNull();
    }

    @Test
    public void acceptEncodingGzip() {
        when(request.headers()).thenReturn(HttpHeaders.of(HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.GZIP);
    }

    @Test
    public void acceptEncodingDeflate() {
        when(request.headers()).thenReturn(HttpHeaders.of(HttpHeaderNames.ACCEPT_ENCODING, "deflate"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.DEFLATE);
    }

    @Test
    public void acceptEncodingBoth() {
        when(request.headers()).thenReturn(HttpHeaders.of(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.GZIP);
    }

    @Test
    public void acceptEncodingUnknown() {
        when(request.headers()).thenReturn(HttpHeaders.of(HttpHeaderNames.ACCEPT_ENCODING, "piedpiper"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isNull();
    }

}
