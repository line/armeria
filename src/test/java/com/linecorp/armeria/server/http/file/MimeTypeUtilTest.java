package com.linecorp.armeria.server.http.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.net.MediaType;

public class MimeTypeUtilTest {

    @Test
    public void knownExtensions() {
        assertThat(MediaType.PNG.is(MimeTypeUtil.guessFromPath("image.png"))).isTrue();
        assertThat(MediaType.PNG.is(MimeTypeUtil.guessFromPath("/static/image.png"))).isTrue();
        assertThat(MediaType.PDF.is(MimeTypeUtil.guessFromPath("document.pdf"))).isTrue();
    }

    @Test
    public void guessed() {
        assertThat(MediaType.ZIP.is(MimeTypeUtil.guessFromPath("bundle.zip"))).isTrue();
    }

    @Test
    public void unknown() {
        assertThat(MimeTypeUtil.guessFromPath("unknown.extension")).isNull();
    }
}
