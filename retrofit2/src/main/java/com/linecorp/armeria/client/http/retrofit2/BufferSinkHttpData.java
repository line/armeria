package com.linecorp.armeria.client.http.retrofit2;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

import com.linecorp.armeria.common.http.HttpData;

import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.Source;
import okio.Timeout;

class BufferSinkHttpData implements BufferedSink, HttpData {
    private final int contentLength;
    private final byte[] bytes;

    BufferSinkHttpData(int contentLength) {
        this.contentLength = contentLength;
        bytes = new byte[contentLength];
    }

    @Override
    public Buffer buffer() {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink write(ByteString byteString) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink write(byte[] source) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink write(byte[] source, int offset, int byteCount) throws IOException {
        System.arraycopy(source, offset, bytes, 0, byteCount);
        return this;
    }

    @Override
    public BufferedSink write(Source source, long byteCount) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public long writeAll(Source source) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeUtf8(String string) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeUtf8(String string, int beginIndex, int endIndex) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeUtf8CodePoint(int codePoint) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeString(String string, Charset charset) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeString(String string, int beginIndex, int endIndex, Charset charset)
            throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeByte(int b) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeShort(int s) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeShortLe(int s) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeInt(int i) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeIntLe(int i) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeLong(long v) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeLongLe(long v) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeDecimalLong(long v) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink writeHexadecimalUnsignedLong(long v) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public void flush() throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public Timeout timeout() {
        throw new IllegalStateException();
    }

    @Override
    public void close() throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink emit() throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public BufferedSink emitCompleteSegments() throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public OutputStream outputStream() {
        throw new IllegalStateException();
    }

    @Override
    public boolean isEndOfStream() {
        return false;
    }

    @Override
    public byte[] array() {
        return bytes;
    }

    @Override
    public int offset() {
        return 0;
    }

    @Override
    public int length() {
        return contentLength;
    }
}
