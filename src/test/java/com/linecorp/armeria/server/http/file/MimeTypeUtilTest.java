package com.linecorp.armeria.server.http.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.net.MediaType;

public class MimeTypeUtilTest {

    @Test
    public void knownExtensions() {
        assertThat(MediaType.PNG.is(MimeTypeUtil.guessFromPath("image.png", false))).isTrue();
        assertThat(MediaType.PNG.is(MimeTypeUtil.guessFromPath("/static/image.png", false))).isTrue();
        assertThat(MediaType.PDF.is(MimeTypeUtil.guessFromPath("document.pdf", false))).isTrue();
    }

    @Test
    public void preCompressed() {
        assertThat(MediaType.PNG.is(MimeTypeUtil.guessFromPath("image.png.gz", true))).isTrue();
        assertThat(MediaType.PNG.is(MimeTypeUtil.guessFromPath("/static/image.png.br", true))).isTrue();
        assertThat(MediaType.OCTET_STREAM.is(MimeTypeUtil.guessFromPath("image.png.gz", false))).isTrue();
    }

    @Test
    public void guessed() {
        assertThat(MediaType.ZIP.is(MimeTypeUtil.guessFromPath("bundle.zip", false))).isTrue();
    }

    @Test
    public void unknown() {
        assertThat(MimeTypeUtil.guessFromPath("unknown.extension", false)).isNull();
    }
}
