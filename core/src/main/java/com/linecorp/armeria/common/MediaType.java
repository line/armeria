/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linecorp.armeria.common;

import static com.google.common.base.CharMatcher.ascii;
import static com.google.common.base.CharMatcher.javaIsoControl;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Joiner.MapJoiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents an <a href="https://en.wikipedia.org/wiki/Internet_media_type">Internet Media Type</a>
 * (also known as a MIME Type or Content Type). This class also supports the concept of media ranges
 * <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.1">defined by HTTP/1.1</a>.
 * As such, the {@code *} character is treated as a wildcard and is used to represent any acceptable
 * type or subtype value. A media type may not have wildcard type with a declared subtype. The
 * {@code *} character has no special meaning as part of a parameter. All values for type, subtype,
 * parameter attributes or parameter values must be valid according to RFCs
 * <a href="https://datatracker.ietf.org/doc/rfc2045/">2045</a> and
 * <a href="https://datatracker.ietf.org/doc/rfc2046/">2046</a>.
 *
 * <p>All portions of the media type that are case-insensitive (type, subtype, parameter attributes)
 * are normalized to lowercase. The value of the {@code charset} parameter is normalized to
 * lowercase, but all others are left as-is.
 *
 * <p>Note that this specifically does <strong>not</strong> represent the value of the MIME
 * {@code Content-Type} header and as such has no support for header-specific considerations such as
 * line folding and comments.
 *
 * <p>For media types that take a charset the predefined constants default to UTF-8 and have a
 * "_UTF_8" suffix. To get a version without a character set, use {@link #withoutParameters}.
 *
 * @author Gregory Kick
 */
@JsonSerialize(using = MediaTypeJsonSerializer.class)
@JsonDeserialize(using = MediaTypeJsonDeserializer.class)
public final class MediaType {

    // Forked from Guava at 261ac7afbf04dce2bd7e20a2085338e1f9a857d8

    private static final String CHARSET_ATTRIBUTE = "charset";
    private static final ImmutableListMultimap<String, String> UTF_8_CONSTANT_PARAMETERS =
            ImmutableListMultimap.of(CHARSET_ATTRIBUTE, Ascii.toLowerCase(UTF_8.name()));

    /** Matcher for type, subtype and attributes. */
    private static final CharMatcher TOKEN_MATCHER =
            ascii()
                    .and(javaIsoControl().negate())
                    .and(CharMatcher.isNot(' '))
                    .and(CharMatcher.noneOf("()<>@,;:\\\"/[]?="));

    private static final CharMatcher QUOTED_TEXT_MATCHER = ascii().and(CharMatcher.noneOf("\"\\\r"));

    /*
     * This matches the same characters as linear-white-space from RFC 822, but we make no effort to
     * enforce any particular rules with regards to line folding as stated in the class docs.
     */
    private static final CharMatcher LINEAR_WHITE_SPACE = CharMatcher.anyOf(" \t\r\n");

    // TODO(gak): make these public?
    private static final String APPLICATION_TYPE = "application";
    private static final String AUDIO_TYPE = "audio";
    private static final String IMAGE_TYPE = "image";
    private static final String TEXT_TYPE = "text";
    private static final String VIDEO_TYPE = "video";
    private static final String FONT_TYPE = "font";
    private static final String MULTIPART_TYPE = "multipart";

    private static final String WILDCARD = "*";
    private static final String Q = "q";

    private static final Map<MediaType, MediaType> KNOWN_TYPES = Maps.newHashMap();

    private static MediaType createConstant(String type, String subtype) {
        MediaType mediaType =
                addKnownType(new MediaType(type, subtype, ImmutableListMultimap.of()));
        mediaType.parsedCharset = null;
        return mediaType;
    }

    private static MediaType createConstantUtf8(String type, String subtype) {
        MediaType mediaType = addKnownType(new MediaType(type, subtype, UTF_8_CONSTANT_PARAMETERS));
        mediaType.parsedCharset = UTF_8;
        return mediaType;
    }

    private static MediaType addKnownType(MediaType mediaType) {
        KNOWN_TYPES.put(mediaType, mediaType);
        return mediaType;
    }

    /*
     * The following constants are grouped by their type and ordered alphabetically by the constant
     * name within that type. The constant name should be a sensible identifier that is closest to the
     * "common name" of the media. This is often, but not necessarily the same as the subtype.
     *
     * Be sure to declare all constants with the type and subtype in all lowercase. For types that
     * take a charset (e.g. all text/* types), default to UTF-8 and suffix the constant name with
     * "_UTF_8".
     */

    public static final MediaType ANY_TYPE = createConstant(WILDCARD, WILDCARD);
    public static final MediaType ANY_TEXT_TYPE = createConstant(TEXT_TYPE, WILDCARD);
    public static final MediaType ANY_IMAGE_TYPE = createConstant(IMAGE_TYPE, WILDCARD);
    public static final MediaType ANY_AUDIO_TYPE = createConstant(AUDIO_TYPE, WILDCARD);
    public static final MediaType ANY_VIDEO_TYPE = createConstant(VIDEO_TYPE, WILDCARD);
    public static final MediaType ANY_APPLICATION_TYPE = createConstant(APPLICATION_TYPE, WILDCARD);
    /**
     * Wildcard matching any "font" top-level media type.
     */
    public static final MediaType ANY_FONT_TYPE = createConstant(FONT_TYPE, WILDCARD);
    public static final MediaType ANY_MULTIPART_TYPE = createConstant(MULTIPART_TYPE, WILDCARD);

    /* text types */
    public static final MediaType CACHE_MANIFEST_UTF_8 =
            createConstantUtf8(TEXT_TYPE, "cache-manifest");
    public static final MediaType CSS_UTF_8 = createConstantUtf8(TEXT_TYPE, "css");
    public static final MediaType CSV_UTF_8 = createConstantUtf8(TEXT_TYPE, "csv");
    public static final MediaType HTML_UTF_8 = createConstantUtf8(TEXT_TYPE, "html");
    public static final MediaType I_CALENDAR_UTF_8 = createConstantUtf8(TEXT_TYPE, "calendar");
    public static final MediaType PLAIN_TEXT_UTF_8 = createConstantUtf8(TEXT_TYPE, "plain");

    /**
     * The <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-7.1.2">text/plain</a> content type is
     * the generic subtype for plain text.
     */
    public static final MediaType PLAIN_TEXT = createConstant(TEXT_TYPE, "plain");

    /**
     * As described in <a href="https://www.w3.org/TR/eventsource/">Server-Sent Events</a>, this constant
     * ({@code text/event-stream}) is used when sending notifications in the form of DOM events from a server.
     */
    public static final MediaType EVENT_STREAM = createConstant(TEXT_TYPE, "event-stream");

    /**
     * <a href="https://datatracker.ietf.org/doc/rfc4329/">RFC 4329</a> declares {@link
     * #JAVASCRIPT_UTF_8 application/javascript} to be the correct media type for JavaScript, but this
     * may be necessary in certain situations for compatibility.
     */
    public static final MediaType TEXT_JAVASCRIPT_UTF_8 = createConstantUtf8(TEXT_TYPE, "javascript");
    /**
     * <a href="http://www.iana.org/assignments/media-types/text/tab-separated-values">Tab separated
     * values</a>.
     */
    public static final MediaType TSV_UTF_8 = createConstantUtf8(TEXT_TYPE, "tab-separated-values");

    public static final MediaType VCARD_UTF_8 = createConstantUtf8(TEXT_TYPE, "vcard");

    /**
     * UTF-8 encoded <a href="https://en.wikipedia.org/wiki/Wireless_Markup_Language">Wireless Markup
     * Language</a>.
     */
    public static final MediaType WML_UTF_8 = createConstantUtf8(TEXT_TYPE, "vnd.wap.wml");

    /**
     * As described in <a href="https://datatracker.ietf.org/doc/rfc3023/">RFC 3023</a>, this constant
     * ({@code text/xml}) is used for XML documents that are "readable by casual users." {@link
     * #APPLICATION_XML_UTF_8} is provided for documents that are intended for applications.
     */
    public static final MediaType XML_UTF_8 = createConstantUtf8(TEXT_TYPE, "xml");

    /**
     * As described in <a href="https://w3c.github.io/webvtt/#iana-text-vtt">the VTT spec</a>, this is
     * used for Web Video Text Tracks (WebVTT) files, used with the HTML5 track element.
     */
    public static final MediaType VTT_UTF_8 = createConstantUtf8(TEXT_TYPE, "vtt");

    /* image types */
    /**
     * <a href="https://en.wikipedia.org/wiki/BMP_file_format">Bitmap file format</a> ({@code bmp}
     * files).
     */
    public static final MediaType BMP = createConstant(IMAGE_TYPE, "bmp");

    /**
     * The <a href="https://en.wikipedia.org/wiki/Camera_Image_File_Format">Canon Image File
     * Format</a> ({@code crw} files), a widely-used "raw image" format for cameras. It is found in
     * {@code /etc/mime.types}, e.g. in <a href=
     * "http://anonscm.debian.org/gitweb/?p=collab-maint/mime-support.git;a=blob;f=mime.types;hb=HEAD"
     * >Debian 3.48-1</a>.
     */
    public static final MediaType CRW = createConstant(IMAGE_TYPE, "x-canon-crw");

    public static final MediaType GIF = createConstant(IMAGE_TYPE, "gif");
    public static final MediaType ICO = createConstant(IMAGE_TYPE, "vnd.microsoft.icon");
    public static final MediaType JPEG = createConstant(IMAGE_TYPE, "jpeg");
    public static final MediaType PNG = createConstant(IMAGE_TYPE, "png");

    /**
     * The Photoshop File Format ({@code psd} files) as defined by <a
     * href="http://www.iana.org/assignments/media-types/image/vnd.adobe.photoshop">IANA</a>, and
     * found in {@code /etc/mime.types}, e.g. <a
     * href="http://svn.apache.org/repos/asf/httpd/httpd/branches/1.3.x/conf/mime.types"></a> of the
     * Apache <a href="http://httpd.apache.org/">HTTPD project</a>; for the specification, see <a
     * href="http://www.adobe.com/devnet-apps/photoshop/fileformatashtml/PhotoshopFileFormats.htm">
     * Adobe Photoshop Document Format</a> and <a
     * href="http://en.wikipedia.org/wiki/Adobe_Photoshop#File_format">Wikipedia</a>; this is the
     * regular output/input of Photoshop (which can also export to various image formats; note that
     * files with extension "PSB" are in a distinct but related format).
     *
     * <p>This is a more recent replacement for the older, experimental type {@code x-photoshop}: <a
     * href="https://datatracker.ietf.org/doc/html/rfc2046#section-6">RFC-2046.6</a>.
     */
    public static final MediaType PSD = createConstant(IMAGE_TYPE, "vnd.adobe.photoshop");

    public static final MediaType SVG_UTF_8 = createConstantUtf8(IMAGE_TYPE, "svg+xml");
    public static final MediaType TIFF = createConstant(IMAGE_TYPE, "tiff");

    /**
     * <a href="https://en.wikipedia.org/wiki/WebP">WebP image format</a>.
     */
    public static final MediaType WEBP = createConstant(IMAGE_TYPE, "webp");

    /**
     * <a href="https://www.iana.org/assignments/media-types/image/heif">HEIF image format</a>.
     */
    public static final MediaType HEIF = createConstant(IMAGE_TYPE, "heif");

    /**
     * <a href="https://datatracker.ietf.org/doc/rfc3745/">JP2K image format</a>.
     */
    public static final MediaType JP2K = createConstant(IMAGE_TYPE, "jp2");

    /**
     * <a href="https://aomediacodec.github.io/av1-avif/">AVIF image format</a>.
     */
    public static final MediaType AVIF = createConstant(IMAGE_TYPE, "avif");

    /* audio types */
    public static final MediaType MP4_AUDIO = createConstant(AUDIO_TYPE, "mp4");
    public static final MediaType MPEG_AUDIO = createConstant(AUDIO_TYPE, "mpeg");
    public static final MediaType OGG_AUDIO = createConstant(AUDIO_TYPE, "ogg");
    public static final MediaType WEBM_AUDIO = createConstant(AUDIO_TYPE, "webm");

    /**
     * L16 audio, as defined by <a href="https://datatracker.ietf.org/doc/rfc2586/">RFC 2586</a>.
     */
    public static final MediaType L16_AUDIO = createConstant(AUDIO_TYPE, "l16");

    /**
     * L24 audio, as defined by <a href="https://datatracker.ietf.org/doc/rfc3190/">RFC 3190</a>.
     */
    public static final MediaType L24_AUDIO = createConstant(AUDIO_TYPE, "l24");

    /**
     * Basic Audio, as defined by <a href="https://datatracker.ietf.org/doc/html/rfc2046#section-4.3">RFC
     * 2046</a>.
     */
    public static final MediaType BASIC_AUDIO = createConstant(AUDIO_TYPE, "basic");

    /**
     * Advanced Audio Coding. For more information, see <a
     * href="https://en.wikipedia.org/wiki/Advanced_Audio_Coding">Advanced Audio Coding</a>.
     */
    public static final MediaType AAC_AUDIO = createConstant(AUDIO_TYPE, "aac");

    /**
     * Vorbis Audio, as defined by <a href="https://datatracker.ietf.org/doc/rfc5215/">RFC 5215</a>.
     */
    public static final MediaType VORBIS_AUDIO = createConstant(AUDIO_TYPE, "vorbis");

    /**
     * Windows Media Audio. For more information, see <a
     * href="https://msdn.microsoft.com/en-us/library/windows/desktop/dd562994(v=vs.85).aspx">file
     * name extensions for Windows Media metafiles</a>.
     */
    public static final MediaType WMA_AUDIO = createConstant(AUDIO_TYPE, "x-ms-wma");

    /**
     * Windows Media metafiles. For more information, see <a
     * href="https://msdn.microsoft.com/en-us/library/windows/desktop/dd562994(v=vs.85).aspx">file
     * name extensions for Windows Media metafiles</a>.
     */
    public static final MediaType WAX_AUDIO = createConstant(AUDIO_TYPE, "x-ms-wax");

    /**
     * Real Audio. For more information, see <a
     * href="http://service.real.com/help/faq/rp8/configrp8win.html">this link</a>.
     */
    public static final MediaType VND_REAL_AUDIO = createConstant(AUDIO_TYPE, "vnd.rn-realaudio");

    /**
     * WAVE format, as defined by <a href="https://datatracker.ietf.org/doc/rfc2361/">RFC 2361</a>.
     */
    public static final MediaType VND_WAVE_AUDIO = createConstant(AUDIO_TYPE, "vnd.wave");

    /* video types */
    public static final MediaType MP4_VIDEO = createConstant(VIDEO_TYPE, "mp4");
    public static final MediaType MPEG_VIDEO = createConstant(VIDEO_TYPE, "mpeg");
    public static final MediaType OGG_VIDEO = createConstant(VIDEO_TYPE, "ogg");
    public static final MediaType QUICKTIME = createConstant(VIDEO_TYPE, "quicktime");
    public static final MediaType WEBM_VIDEO = createConstant(VIDEO_TYPE, "webm");
    public static final MediaType WMV = createConstant(VIDEO_TYPE, "x-ms-wmv");

    /**
     * Flash video. For more information, see <a href=
     * "http://help.adobe.com/en_US/ActionScript/3.0_ProgrammingAS3/WS5b3ccc516d4fbf351e63e3d118a9b90204-7d48.html"
     * >this link</a>.
     */
    public static final MediaType FLV_VIDEO = createConstant(VIDEO_TYPE, "x-flv");

    /**
     * The 3GP multimedia container format. For more information, see <a
     * href="ftp://www.3gpp.org/tsg_sa/TSG_SA/TSGS_23/Docs/PDF/SP-040065.pdf#page=10">3GPP TS
     * 26.244</a>.
     */
    public static final MediaType THREE_GPP_VIDEO = createConstant(VIDEO_TYPE, "3gpp");

    /**
     * The 3G2 multimedia container format. For more information, see <a
     * href="http://www.3gpp2.org/Public_html/specs/C.S0050-B_v1.0_070521.pdf#page=16">3GPP2
     * C.S0050-B</a>.
     */
    public static final MediaType THREE_GPP2_VIDEO = createConstant(VIDEO_TYPE, "3gpp2");

    /* application types */
    /**
     * As described in <a href="https://datatracker.ietf.org/doc/rfc3023/">RFC 3023</a>, this constant
     * ({@code application/xml}) is used for XML documents that are "unreadable by casual users."
     * {@link #XML_UTF_8} is provided for documents that may be read by users.
     */
    public static final MediaType APPLICATION_XML_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "xml");

    public static final MediaType ATOM_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "atom+xml");
    public static final MediaType BZIP2 = createConstant(APPLICATION_TYPE, "x-bzip2");

    /**
     * Files in the <a href="https://www.dartlang.org/articles/embedding-in-html/">dart</a>.
     */
    public static final MediaType DART_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "dart");

    /**
     * <a href="https://goo.gl/2QoMvg">Apple Passbook</a>.
     */
    public static final MediaType APPLE_PASSBOOK =
            createConstant(APPLICATION_TYPE, "vnd.apple.pkpass");

    /**
     * <a href="http://en.wikipedia.org/wiki/Embedded_OpenType">Embedded OpenType</a> fonts. This is
     * <a href="http://www.iana.org/assignments/media-types/application/vnd.ms-fontobject">registered
     * </a> with the IANA.
     */
    public static final MediaType EOT = createConstant(APPLICATION_TYPE, "vnd.ms-fontobject");

    /**
     * As described in the <a href="http://idpf.org/epub">International Digital Publishing Forum</a>
     * EPUB is the distribution and interchange format standard for digital publications and
     * documents. This media type is defined in the <a
     * href="http://www.idpf.org/epub/30/spec/epub30-ocf.html">EPUB Open Container Format</a>
     * specification.
     */
    public static final MediaType EPUB = createConstant(APPLICATION_TYPE, "epub+zip");

    public static final MediaType FORM_DATA =
            createConstant(APPLICATION_TYPE, "x-www-form-urlencoded");

    /**
     * A {@link MediaType} constant representing {@code multipart/alternative} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-7.2.3">RFC 1521:
     * MIME Part One: Mechanisms for Specifying and Describing the Format of Internet Message Bodies</a>
     */
    public static final MediaType MULTIPART_ALTERNATIVE = createConstant(MULTIPART_TYPE, "alternative");

    /**
     * A {@link MediaType} constant representing {@code multipart/digest} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-7.2.4">RFC 1521:
     * MIME Part One: Mechanisms for Specifying and Describing the Format of Internet Message Bodies</a>
     */
    public static final MediaType MULTIPART_DIGEST = createConstant(MULTIPART_TYPE, "digest");

    /**
     * A {@link MediaType} constant representing {@code multipart/encrypted} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1847#section-2.2">RFC 1847:
     * Security Multiparts for MIME: Multipart/Signed and Multipart/Encrypted</a>
     */
    public static final MediaType MULTIPART_ENCRYPTED = createConstant(MULTIPART_TYPE, "encrypted");

    /**
     * A {@link MediaType} constant representing {@code multipart/form-data} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1867">RFC 1867:
     * Form-based File Upload in HTML</a>
     */
    public static final MediaType MULTIPART_FORM_DATA = createConstant(MULTIPART_TYPE, "form-data");

    /**
     * A {@link MediaType} constant representing {@code multipart/mixed} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-7.2.2">RFC 1521:
     * MIME Part One: Mechanisms for Specifying and Describing the Format of Internet Message Bodies</a>
     */
    public static final MediaType MULTIPART_MIXED = createConstant(MULTIPART_TYPE, "mixed");

    /**
     * A {@link MediaType} constant representing {@code multipart/parallel} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1521#section-7.2.5">RFC 1521:
     * MIME Part One: Mechanisms for Specifying and Describing the Format of Internet Message Bodies</a>
     */
    public static final MediaType MULTIPART_PARALLEL = createConstant(MULTIPART_TYPE, "parallel");

    /**
     * A {@link MediaType} constant representing {@code multipart/related} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc2112">RFC 2112:
     * The MIME Multipart/Related Content-type</a>
     */
    public static final MediaType MULTIPART_RELATED = createConstant(MULTIPART_TYPE, "related");

    /**
     * A {@link MediaType} constant representing {@code multipart/signed} media type.
     * As described in <a href="https://datatracker.ietf.org/doc/html/rfc1847#section-2.1">RFC 1847:
     * Security Multiparts for MIME: Multipart/Signed and Multipart/Encrypted</a>
     */
    public static final MediaType MULTIPART_SIGNED = createConstant(MULTIPART_TYPE, "signed");

    /**
     * As described in <a href="https://www.rsa.com/rsalabs/node.asp?id=2138">PKCS #12: Personal
     * Information Exchange Syntax Standard</a>, PKCS #12 defines an archive file format for storing
     * many cryptography objects as a single file.
     */
    public static final MediaType KEY_ARCHIVE = createConstant(APPLICATION_TYPE, "pkcs12");

    /**
     * This is a non-standard media type, but is commonly used in serving hosted binary files as it is
     * <a href="http://code.google.com/p/browsersec/wiki/Part2#Survey_of_content_sniffing_behaviors">
     * known not to trigger content sniffing in current browsers</a>. It <i>should not</i> be used in
     * other situations as it is not specified by any RFC and does not appear in the <a
     * href="http://www.iana.org/assignments/media-types">/IANA MIME Media Types</a> list. Consider
     * {@link #OCTET_STREAM} for binary data that is not being served to a browser.
     */
    public static final MediaType APPLICATION_BINARY = createConstant(APPLICATION_TYPE, "binary");

    /**
     * <a href="https://datatracker.ietf.org/doc/rfc7946/">GeoJSON Format</a>, a geospatial data interchange format
     * based on JSON.
     */
    public static final MediaType GEO_JSON = createConstant(APPLICATION_TYPE, "geo+json");

    /**
     * This constant is used for advertising the capabilities of a Git server,
     * as described in <a href="https://git-scm.com/docs/http-protocol/2.34.0#_smart_clients">Smart Clients</a>.
     */
    @UnstableApi
    public static final MediaType GIT_UPLOAD_PACK_ADVERTISEMENT =
            createConstant(APPLICATION_TYPE, "x-git-upload-pack-advertisement");

    /**
     * This constant is used for requesting a Git packfile from a Git server, as described in
     * <a href="https://git-scm.com/docs/http-protocol/2.34.0#_smart_service_git_upload_pack">
     * Smart Service git-upload-pack</a>.
     */
    @UnstableApi
    public static final MediaType GIT_UPLOAD_PACK_REQUEST =
            createConstant(APPLICATION_TYPE, "x-git-upload-pack-request");

    /**
     * This constant is used for responding with a Git packfile from a Git server, as described in
     * <a href="https://git-scm.com/docs/http-protocol/2.34.0#_smart_service_git_upload_pack">
     * Smart Service git-upload-pack</a>.
     */
    @UnstableApi
    public static final MediaType GIT_UPLOAD_PACK_RESULT =
            createConstant(APPLICATION_TYPE, "x-git-upload-pack-result");

    public static final MediaType GZIP = createConstant(APPLICATION_TYPE, "x-gzip");

    /**
     * <a href="https://datatracker.ietf.org/doc/html/rfc7932">Brotli Compression format</a>, a lossless data
     * compression format.
     */
    public static final MediaType BROTLI = createConstant(APPLICATION_TYPE, "brotli");

    /**
     * <a href="https://datatracker.ietf.org/doc/html/rfc8478">Zstandard Compression format</a>, a lossless data
     * compression format.
     */
    public static final MediaType ZSTD = createConstant(APPLICATION_TYPE, "zstd");

    /**
     * <a href="https://datatracker.ietf.org/doc/html/draft-kelly-json-hal-08#section-3">JSON Hypertext
     * Application Language (HAL) documents</a>.
     */
    public static final MediaType HAL_JSON = createConstant(APPLICATION_TYPE, "hal+json");

    /**
     * <a href="https://datatracker.ietf.org/doc/rfc4329/">RFC 4329</a> declares this to be the
     * correct media type for JavaScript, but {@link #TEXT_JAVASCRIPT_UTF_8 text/javascript} may be
     * necessary in certain situations for compatibility.
     */
    public static final MediaType JAVASCRIPT_UTF_8 =
            createConstantUtf8(APPLICATION_TYPE, "javascript");

    /**
     * For <a href="https://datatracker.ietf.org/doc/rfc7515/">JWS or JWE objects using the Compact
     * Serialization</a>.
     */
    public static final MediaType JOSE = createConstant(APPLICATION_TYPE, "jose");

    /**
     * For <a href="https://datatracker.ietf.org/doc/rfc7515/">JWS or JWE objects using the JSON
     * Serialization</a>.
     */
    public static final MediaType JOSE_JSON = createConstant(APPLICATION_TYPE, "jose+json");

    public static final MediaType JSON_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "json");
    public static final MediaType JSON = createConstant(APPLICATION_TYPE, "json");

    /**
     * For <a href="https://tools.ietf.org/html/7519">JWT objects using the compact Serialization</a>.
     */
    public static final MediaType JWT = createConstant(APPLICATION_TYPE, "jwt");

    /**
     * As described in <a href="https://datatracker.ietf.org/doc/rfc6902/">RFC 6902</a>, this constant
     * ({@code application/json-patch+json}) is used for expressing a sequence of operations to apply
     * to a JavaScript Object Notation(JSON) document.
     */
    public static final MediaType JSON_PATCH = createConstant(APPLICATION_TYPE, "json-patch+json");

    /**
     * As described in <a href="https://datatracker.ietf.org/doc/rfc7464/">RFC 7464</a>, this constant
     * ({@code application/json-seq}) is used for expressing JSON text sequences.
     */
    public static final MediaType JSON_SEQ = createConstant(APPLICATION_TYPE, "json-seq");

    /**
     * As described in <a href="https://jsonlines.org/">JSON Lines</a>,
     * this constant is used for expressing JSON lines.
     */
    public static final MediaType JSON_LINES = createConstant(APPLICATION_TYPE, "x-ndjson");

    /**
     * The <a href="http://www.w3.org/TR/appmanifest/">Manifest for a web application</a>.
     */
    public static final MediaType MANIFEST_JSON_UTF_8 =
            createConstantUtf8(APPLICATION_TYPE, "manifest+json");

    /**
     * <a href="http://www.opengeospatial.org/standards/kml/">OGC KML (Keyhole Markup Language)</a>.
     */
    public static final MediaType KML = createConstant(APPLICATION_TYPE, "vnd.google-earth.kml+xml");

    /**
     * <a href="http://www.opengeospatial.org/standards/kml/">OGC KML (Keyhole Markup Language)</a>,
     * compressed using the ZIP format into KMZ archives.
     */
    public static final MediaType KMZ = createConstant(APPLICATION_TYPE, "vnd.google-earth.kmz");

    /**
     * The <a href="https://datatracker.ietf.org/doc/rfc4155/">mbox database format</a>.
     */
    public static final MediaType MBOX = createConstant(APPLICATION_TYPE, "mbox");

    /**
     * <a href="http://goo.gl/1pGBFm">Apple over-the-air mobile configuration profiles</a>.
     */
    public static final MediaType APPLE_MOBILE_CONFIG =
            createConstant(APPLICATION_TYPE, "x-apple-aspen-config");

    /** <a href="http://goo.gl/XDQ1h2">Microsoft Excel</a> spreadsheets. */
    public static final MediaType MICROSOFT_EXCEL = createConstant(APPLICATION_TYPE, "vnd.ms-excel");

    /**
     * <a href="http://goo.gl/XrTEqG">Microsoft Outlook</a> items.
     */
    public static final MediaType MICROSOFT_OUTLOOK =
            createConstant(APPLICATION_TYPE, "vnd.ms-outlook");

    /** <a href="http://goo.gl/XDQ1h2">Microsoft Powerpoint</a> presentations. */
    public static final MediaType MICROSOFT_POWERPOINT =
            createConstant(APPLICATION_TYPE, "vnd.ms-powerpoint");

    /** <a href="http://goo.gl/XDQ1h2">Microsoft Word</a> documents. */
    public static final MediaType MICROSOFT_WORD = createConstant(APPLICATION_TYPE, "msword");

    /**
     * Media type for <a
     * href="https://en.wikipedia.org/wiki/Dynamic_Adaptive_Streaming_over_HTTP">Dynamic Adaptive
     * Streaming over HTTP (DASH)</a>. This is <a
     * href="https://www.iana.org/assignments/media-types/application/dash+xml">registered</a> with
     * the IANA.
     */
    public static final MediaType MEDIA_PRESENTATION_DESCRIPTION =
            createConstant(APPLICATION_TYPE, "dash+xml");

    /**
     * WASM applications. For more information see <a href="https://webassembly.org/">the Web Assembly
     * overview</a>.
     */
    public static final MediaType WASM_APPLICATION = createConstant(APPLICATION_TYPE, "wasm");

    /**
     * NaCl applications. For more information see <a
     * href="https://developer.chrome.com/native-client/devguide/coding/application-structure">the
     * Developer Guide for Native Client Application Structure</a>.
     */
    public static final MediaType NACL_APPLICATION = createConstant(APPLICATION_TYPE, "x-nacl");

    /**
     * NaCl portable applications. For more information see <a
     * href="https://developer.chrome.com/native-client/devguide/coding/application-structure">the
     * Developer Guide for Native Client Application Structure</a>.
     */
    public static final MediaType NACL_PORTABLE_APPLICATION =
            createConstant(APPLICATION_TYPE, "x-pnacl");

    public static final MediaType OCTET_STREAM = createConstant(APPLICATION_TYPE, "octet-stream");

    public static final MediaType OGG_CONTAINER = createConstant(APPLICATION_TYPE, "ogg");
    public static final MediaType OOXML_DOCUMENT =
            createConstant(
                    APPLICATION_TYPE, "vnd.openxmlformats-officedocument.wordprocessingml.document");
    public static final MediaType OOXML_PRESENTATION =
            createConstant(
                    APPLICATION_TYPE, "vnd.openxmlformats-officedocument.presentationml.presentation");
    public static final MediaType OOXML_SHEET =
            createConstant(APPLICATION_TYPE, "vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public static final MediaType OPENDOCUMENT_GRAPHICS =
            createConstant(APPLICATION_TYPE, "vnd.oasis.opendocument.graphics");
    public static final MediaType OPENDOCUMENT_PRESENTATION =
            createConstant(APPLICATION_TYPE, "vnd.oasis.opendocument.presentation");
    public static final MediaType OPENDOCUMENT_SPREADSHEET =
            createConstant(APPLICATION_TYPE, "vnd.oasis.opendocument.spreadsheet");
    public static final MediaType OPENDOCUMENT_TEXT =
            createConstant(APPLICATION_TYPE, "vnd.oasis.opendocument.text");

    /**
     * <a href="https://datatracker.ietf.org/doc/draft-ellermann-opensearch/">OpenSearch</a>
     * Description files are XML files that describe how a website can be used as a search engine by
     * consumers (e.g. web browsers).
     */
    public static final MediaType OPENSEARCH_DESCRIPTION_UTF_8 =
            createConstantUtf8(APPLICATION_TYPE, "opensearchdescription+xml");

    public static final MediaType PDF = createConstant(APPLICATION_TYPE, "pdf");
    public static final MediaType POSTSCRIPT = createConstant(APPLICATION_TYPE, "postscript");
    /**
     * <a href="https://developers.google.com/protocol-buffers">Protocol buffers</a>.
     */
    public static final MediaType PROTOBUF = createConstant(APPLICATION_TYPE, "protobuf");
    public static final MediaType X_PROTOBUF = createConstant(APPLICATION_TYPE, "x-protobuf");
    public static final MediaType X_GOOGLE_PROTOBUF = createConstant(APPLICATION_TYPE, "x-google-protobuf");

    /**
     * <a href="https://en.wikipedia.org/wiki/RDF/XML">RDF/XML</a> documents, which are XML
     * serializations of <a
     * href="https://en.wikipedia.org/wiki/Resource_Description_Framework">Resource Description
     * Framework</a> graphs.
     */
    public static final MediaType RDF_XML_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "rdf+xml");

    public static final MediaType RTF_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "rtf");
    /**
     * <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a> declares {@link #FONT_SFNT
     * font/sfnt} to be the correct media type for SFNT, but this may be necessary in certain
     * situations for compatibility.
     */
    public static final MediaType SFNT = createConstant(APPLICATION_TYPE, "font-sfnt");

    public static final MediaType SHOCKWAVE_FLASH =
            createConstant(APPLICATION_TYPE, "x-shockwave-flash");

    /**
     * {@code skp} files produced by the 3D Modeling software <a
     * href="https://www.sketchup.com/">SketchUp</a>.
     */
    public static final MediaType SKETCHUP = createConstant(APPLICATION_TYPE, "vnd.sketchup.skp");
    /**
     * As described in <a href="https://datatracker.ietf.org/doc/rfc3902/">RFC 3902</a>, this constant
     * ({@code application/soap+xml}) is used to identify SOAP 1.2 message envelopes that have been
     * serialized with XML 1.0.
     *
     * <p>For SOAP 1.1 messages, see {@code XML_UTF_8} per <a
     * href="http://www.w3.org/TR/2000/NOTE-SOAP-20000508/">W3C Note on Simple Object Access Protocol
     * (SOAP) 1.1</a>
     */
    public static final MediaType SOAP_XML_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "soap+xml");

    public static final MediaType TAR = createConstant(APPLICATION_TYPE, "x-tar");

    /**
     * <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a> declares {@link #FONT_WOFF
     * font/woff} to be the correct media type for WOFF, but this may be necessary in certain
     * situations for compatibility.
     */
    public static final MediaType WOFF = createConstant(APPLICATION_TYPE, "font-woff");

    /**
     * <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a> declares {@link #FONT_WOFF2
     * font/woff2} to be the correct media type for WOFF2, but this may be necessary in certain
     * situations for compatibility.
     */
    public static final MediaType WOFF2 = createConstant(APPLICATION_TYPE, "font-woff2");

    public static final MediaType XHTML_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "xhtml+xml");

    /**
     * Extensible Resource Descriptors. This is not yet registered with the IANA, but it is specified
     * by OASIS in the <a href="http://docs.oasis-open.org/xri/xrd/v1.0/cd02/xrd-1.0-cd02.html">XRD
     * definition</a> and implemented in projects such as <a
     * href="http://code.google.com/p/webfinger/">WebFinger</a>.
     */
    public static final MediaType XRD_UTF_8 = createConstantUtf8(APPLICATION_TYPE, "xrd+xml");

    public static final MediaType ZIP = createConstant(APPLICATION_TYPE, "zip");

    /* font types */

    /**
     * A collection of font outlines as defined by <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC
     * 8081</a>.
     */
    public static final MediaType FONT_COLLECTION = createConstant(FONT_TYPE, "collection");

    /**
     * <a href="https://en.wikipedia.org/wiki/OpenType">Open Type Font Format</a> (OTF) as defined by
     * <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a>.
     */
    public static final MediaType FONT_OTF = createConstant(FONT_TYPE, "otf");

    /**
     * <a href="https://en.wikipedia.org/wiki/SFNT">Spline or Scalable Font Format</a> (SFNT). <a
     * href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a> declares this to be the correct media
     * type for SFNT, but {@link #SFNT application/font-sfnt} may be necessary in certain situations
     * for compatibility.
     */
    public static final MediaType FONT_SFNT = createConstant(FONT_TYPE, "sfnt");

    /**
     * <a href="https://en.wikipedia.org/wiki/TrueType">True Type Font Format</a> (TTF) as defined by
     * <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a>.
     */
    public static final MediaType FONT_TTF = createConstant(FONT_TYPE, "ttf");

    /**
     * <a href="http://en.wikipedia.org/wiki/Web_Open_Font_Format">Web Open Font Format</a> (WOFF). <a
     * href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a> declares this to be the correct media
     * type for WOFF, but {@link #WOFF application/font-woff} may be necessary in certain situations
     * for compatibility.
     */
    public static final MediaType FONT_WOFF = createConstant(FONT_TYPE, "woff");

    /**
     * <a href="http://en.wikipedia.org/wiki/Web_Open_Font_Format">Web Open Font Format</a> (WOFF2).
     * <a href="https://datatracker.ietf.org/doc/rfc8081/">RFC 8081</a> declares this to be the correct
     * media type for WOFF2, but {@link #WOFF2 application/font-woff2} may be necessary in certain
     * situations for compatibility.
     */
    public static final MediaType FONT_WOFF2 = createConstant(FONT_TYPE, "woff2");

    /* GraphQL types */
    /**
     * <a href="https://graphql.org/learn/serving-over-http">GraphQL</a>
     */
    public static final MediaType GRAPHQL = createConstant(APPLICATION_TYPE, "graphql");

    /**
     * The GraphQL response content type is changed from {@link #GRAPHQL_JSON} to {@link #GRAPHQL_RESPONSE_JSON}
     * in this PR. <a href="https://github.com/graphql/graphql-over-http/pull/215">Change media type</a>
     *
     * @deprecated Use {@link #GRAPHQL_RESPONSE_JSON} if the client can recognize the media type.
     */
    @Deprecated
    public static final MediaType GRAPHQL_JSON = createConstant(APPLICATION_TYPE, "graphql+json");

    /**
     * <a href="https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#content-types">GraphQL over JSON</a>
     * which is the official GraphQL response content type.
     */
    @UnstableApi
    public static final MediaType GRAPHQL_RESPONSE_JSON =
            createConstant(APPLICATION_TYPE, "graphql-response+json");

    private static final Charset NO_CHARSET = new Charset("NO_CHARSET", null) {
        @Override
        public boolean contains(Charset cs) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CharsetEncoder newEncoder() {
            throw new UnsupportedOperationException();
        }
    };

    private final String type;
    private final String subtype;
    private final ImmutableListMultimap<String, String> parameters;

    @Nullable
    private String toString;

    private int hashCode;

    @Nullable
    private Charset parsedCharset;

    private MediaType(String type, String subtype, ImmutableListMultimap<String, String> parameters) {
        this.type = type;
        this.subtype = subtype;
        this.parameters = parameters;
    }

    /**
     * Returns the top-level media type. For example, {@code "text"} in {@code "text/plain"}.
     */
    public String type() {
        return type;
    }

    /**
     * Returns the media subtype. For example, {@code "plain"} in {@code "text/plain"}.
     */
    public String subtype() {
        return subtype;
    }

    /**
     * Returns a multimap containing the parameters of this media type.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Map<String, List<String>> parameters() {
        return (Map<String, List<String>>) (Map) parameters.asMap();
    }

    private Map<String, ImmutableMultiset<String>> parametersAsMap() {
        return Maps.transformValues(parameters.asMap(), ImmutableMultiset::copyOf);
    }

    /**
     * Returns a {@link Charset} for the value of the charset parameter if it is specified.
     *
     * @return the {@link Charset}, or {@code null} if the charset parameter is not specified.
     *
     * @throws IllegalStateException if multiple charset values have been set for this media type
     * @throws IllegalCharsetNameException if a charset value is present, but illegal
     * @throws UnsupportedCharsetException if a charset value is present, but no support is available
     *                                     in this instance of the Java virtual machine
     */
    @Nullable
    public Charset charset() {
        // racy single-check idiom, this is safe because Optional is immutable.
        Charset local = parsedCharset;
        if (local == null) {
            String value = null;
            local = NO_CHARSET;
            for (String currentValue : parameters.get(CHARSET_ATTRIBUTE)) {
                if (value == null) {
                    value = currentValue;
                    local = Charset.forName(value);
                } else if (!value.equals(currentValue)) {
                    throw new IllegalStateException(
                            "Multiple charset values defined: " + value + ", " + currentValue);
                }
            }
            parsedCharset = local;
        }
        return local != NO_CHARSET ? local : null;
    }

    /**
     * Returns a {@link Charset} for the value of the charset parameter if it is specified.
     *
     * @return the {@link Charset}, or {@code defaultCharset} if the charset parameter is not specified.
     *
     * @throws IllegalStateException if multiple charset values have been set for this media type
     * @throws IllegalCharsetNameException if a charset value is present, but illegal
     * @throws UnsupportedCharsetException if a charset value is present, but no support is available
     *                                     in this instance of the Java virtual machine
     */
    public Charset charset(Charset defaultCharset) {
        requireNonNull(defaultCharset, "defaultCharset");
        return firstNonNull(charset(), defaultCharset);
    }

    /**
     * Returns a new instance with the same type and subtype as this instance, but without any
     * parameters.
     */
    public MediaType withoutParameters() {
        return parameters.isEmpty() ? this : create(type, subtype);
    }

    /**
     * <em>Replaces</em> all parameters with the given parameters.
     *
     * @throws IllegalArgumentException if any parameter or value is invalid
     */
    public MediaType withParameters(Map<String, ? extends Iterable<String>> parameters) {
        final ImmutableListMultimap.Builder<String, String> builder = ImmutableListMultimap.builder();
        for (Map.Entry<String, ? extends Iterable<String>> e : parameters.entrySet()) {
            final String k = e.getKey();
            for (String v : e.getValue()) {
                builder.put(k, v);
            }
        }
        return create(type, subtype, builder.build());
    }

    /**
     * <em>Replaces</em> all parameters with the given attribute with parameters using the given
     * values. If there are no values, any existing parameters with the given attribute are
     * removed.
     *
     * @throws IllegalArgumentException if either {@code attribute} or {@code values} is invalid
     */
    public MediaType withParameters(String attribute, Iterable<String> values) {
        checkNotNull(attribute);
        checkNotNull(values);
        String normalizedAttribute = normalizeToken(attribute);
        ImmutableListMultimap.Builder<String, String> builder = ImmutableListMultimap.builder();
        for (Entry<String, String> entry : parameters.entries()) {
            String key = entry.getKey();
            if (!normalizedAttribute.equals(key)) {
                builder.put(key, entry.getValue());
            }
        }
        for (String value : values) {
            builder.put(normalizedAttribute, normalizeParameterValue(normalizedAttribute, value));
        }
        MediaType mediaType = new MediaType(type, subtype, builder.build());
        // if the attribute isn't charset, we can just inherit the current parsedCharset
        if (!normalizedAttribute.equals(CHARSET_ATTRIBUTE)) {
            mediaType.parsedCharset = parsedCharset;
        }
        // Return one of the constants if the media type is a known type.
        return firstNonNull(KNOWN_TYPES.get(mediaType), mediaType);
    }

    /**
     * <em>Replaces</em> all parameters with the given attribute with a single parameter with the
     * given value. If multiple parameters with the same attributes are necessary use {@link
     * #withParameters(String, Iterable)}. Prefer {@link #withCharset} for setting the {@code charset}
     * parameter when using a {@link Charset} object.
     *
     * @throws IllegalArgumentException if either {@code attribute} or {@code value} is invalid
     */
    public MediaType withParameter(String attribute, String value) {
        return withParameters(attribute, ImmutableSet.of(value));
    }

    /**
     * Returns a new instance with the same type and subtype as this instance, with the {@code
     * charset} parameter set to the {@linkplain Charset#name() name} of the given charset. Only one {@code
     * charset} parameter will be present on the new instance regardless of the number set on this
     * one.
     *
     * <p>If a charset must be specified that is not supported on this JVM (and thus is not
     * representable as a {@link Charset} instance, use {@link #withParameter}.
     */
    public MediaType withCharset(Charset charset) {
        checkNotNull(charset);
        MediaType withCharset = withParameter(CHARSET_ATTRIBUTE, charset.name());
        // precache the charset so we don't need to parse it
        withCharset.parsedCharset = charset;
        return withCharset;
    }

    /**
     * Returns {@code true} if either the type or subtype is the wildcard.
     */
    public boolean hasWildcard() {
        return WILDCARD.equals(type) || WILDCARD.equals(subtype);
    }

    /**
     * Returns the number of wildcards of this {@link MediaType}.
     */
    public int numWildcards() {
        int numWildcards = 0;
        if (WILDCARD.equals(type())) {
            numWildcards++;
        }
        if (WILDCARD.equals(subtype())) {
            numWildcards++;
        }
        return numWildcards;
    }

    /**
     * Returns {@code true} if the type is multipart.
     * Otherwise {@code false}.
     * @see #ANY_MULTIPART_TYPE
     */
    public boolean isMultipart() {
        return ANY_MULTIPART_TYPE.type().equals(type());
    }

    /**
     * Returns {@code true} if this instance falls within the range (as defined by <a
     * href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.1">the HTTP Accept header</a>) given
     * by the argument according to three criteria:
     *
     * <ol>
     *   <li>The type of the argument is the wildcard or equal to the type of this instance.
     *   <li>The subtype of the argument is the wildcard or equal to the subtype of this instance.
     *   <li>All of the parameters present in the argument are present in this instance.
     * </ol>
     *
     * <p>For example:
     *
     * <pre>{@code
     * PLAIN_TEXT_UTF_8.is(PLAIN_TEXT_UTF_8) // true
     * PLAIN_TEXT_UTF_8.is(HTML_UTF_8) // false
     * PLAIN_TEXT_UTF_8.is(ANY_TYPE) // true
     * PLAIN_TEXT_UTF_8.is(ANY_TEXT_TYPE) // true
     * PLAIN_TEXT_UTF_8.is(ANY_IMAGE_TYPE) // false
     * PLAIN_TEXT_UTF_8.is(ANY_TEXT_TYPE.withCharset(UTF_8)) // true
     * PLAIN_TEXT_UTF_8.withoutParameters().is(ANY_TEXT_TYPE.withCharset(UTF_8)) // false
     * PLAIN_TEXT_UTF_8.is(ANY_TEXT_TYPE.withCharset(UTF_16)) // false
     * }</pre>
     *
     * <p>Note that while it is possible to have the same parameter declared multiple times within a
     * media type this method does not consider the number of occurrences of a parameter. For example,
     * {@code "text/plain; charset=UTF-8"} satisfies {@code "text/plain; charset=UTF-8;
     * charset=UTF-8"}.
     */
    public boolean is(MediaType mediaTypeRange) {
        return (mediaTypeRange.type.equals(WILDCARD) || mediaTypeRange.type.equals(type)) &&
               (mediaTypeRange.subtype.equals(WILDCARD) || mediaTypeRange.subtype.equals(subtype)) &&
               parameters.entries().containsAll(mediaTypeRange.parameters.entries());
    }

    /**
     * Returns {@code true} when the subtype is {@link MediaType#JSON} or ends with {@code +json}.
     * Otherwise {@code false}.
     *
     * <pre>{@code
     * JSON.isJson() // true
     * JSON_UTF_8.isJson() // true
     * PLAIN_TEXT_UTF_8.isJson() // false
     * MediaType.parse("application/graphql+json").isJson() // true
     * }</pre>
     */
    public boolean isJson() {
        return is(JSON) || subtype().endsWith("+json");
    }

    /**
     * Returns {@code true} when the subtype is one of {@link MediaType#PROTOBUF}, {@link MediaType#X_PROTOBUF}
     * and {@link MediaType#X_GOOGLE_PROTOBUF}. Otherwise {@code false}.
     *
     * <pre>{@code
     * PROTOBUF.isProtobuf() // true
     * X_PROTOBUF.isProtobuf() // true
     * X_GOOGLE_PROTOBUF.isProtobuf() // true
     * }</pre>
     */
    public boolean isProtobuf() {
        return is(PROTOBUF) || is(X_PROTOBUF)|| is(X_GOOGLE_PROTOBUF);
    }

    /**
     * Returns {@code true} if this {@link MediaType} belongs to the given {@link MediaType}.
     * Similar to what {@link MediaType#is(MediaType)} does except that this one compares the parameters
     * case-insensitively and excludes 'q' parameter.
     */
    public boolean belongsTo(MediaType mediaTypeRange) {
        return (mediaTypeRange.type.equals(WILDCARD) || mediaTypeRange.type.equals(type)) &&
               (mediaTypeRange.subtype.equals(WILDCARD) || mediaTypeRange.subtype.equals(subtype)) &&
               containsAllParameters(mediaTypeRange.parameters(), parameters());
    }

    /**
     * Returns the quality factor of this {@link MediaType}. If it is not specified,
     * {@code defaultValueIfNotSpecified} will be returned.
     */
    public float qualityFactor(float defaultValueIfNotSpecified) {
        // Find 'q' or 'Q'.
        final List<String> qValues = parameters().get(Q);
        if (qValues == null || qValues.isEmpty()) {
            // qvalue does not exist.
            return defaultValueIfNotSpecified;
        }

        try {
            // Parse the qvalue. Make sure it's within the range of [0, 1].
            return Math.max(Math.min(Float.parseFloat(qValues.get(0)), 1.0f), 0.0f);
        } catch (NumberFormatException e) {
            // The range with a malformed qvalue gets the lowest possible preference.
            return 0.0f;
        }
    }

    /**
     * Returns the quality factor of this {@link MediaType}. If it is not specified,
     * {@code 1.0f} will be returned.
     */
    public float qualityFactor() {
        return qualityFactor(1.0f);
    }

    /**
     * Returns a name of this {@link MediaType} only consisting of the type and the sub type.
     */
    public String nameWithoutParameters() {
        return type() + '/' + subtype();
    }

    /**
     * Creates a new media type with the given type and subtype.
     *
     * @throws IllegalArgumentException if type or subtype is invalid or if a wildcard is used for the
     *     type, but not the subtype.
     */
    public static MediaType create(String type, String subtype) {
        MediaType mediaType = create(type, subtype, ImmutableListMultimap.of());
        mediaType.parsedCharset = null;
        return mediaType;
    }

    private static MediaType create(
            String type, String subtype, Multimap<String, String> parameters) {
        checkNotNull(type);
        checkNotNull(subtype);
        checkNotNull(parameters);
        String normalizedType = normalizeToken(type);
        String normalizedSubtype = normalizeToken(subtype);
        checkArgument(
                !WILDCARD.equals(normalizedType) || WILDCARD.equals(normalizedSubtype),
                "A wildcard type cannot be used with a non-wildcard subtype");
        ImmutableListMultimap.Builder<String, String> builder = ImmutableListMultimap.builder();
        for (Entry<String, String> entry : parameters.entries()) {
            String attribute = normalizeToken(entry.getKey());
            builder.put(attribute, normalizeParameterValue(attribute, entry.getValue()));
        }
        MediaType mediaType = new MediaType(normalizedType, normalizedSubtype, builder.build());
        // Return one of the constants if the media type is a known type.
        return firstNonNull(KNOWN_TYPES.get(mediaType), mediaType);
    }

    /**
     * Creates a media type with the "application" type and the given subtype.
     *
     * @throws IllegalArgumentException if subtype is invalid
     */
    static MediaType createApplicationType(String subtype) {
        return create(APPLICATION_TYPE, subtype);
    }

    /**
     * Creates a media type with the "audio" type and the given subtype.
     *
     * @throws IllegalArgumentException if subtype is invalid
     */
    static MediaType createAudioType(String subtype) {
        return create(AUDIO_TYPE, subtype);
    }

    /**
     * Creates a media type with the "font" type and the given subtype.
     *
     * @throws IllegalArgumentException if subtype is invalid
     */
    static MediaType createFontType(String subtype) {
        return create(FONT_TYPE, subtype);
    }

    /**
     * Creates a media type with the "image" type and the given subtype.
     *
     * @throws IllegalArgumentException if subtype is invalid
     */
    static MediaType createImageType(String subtype) {
        return create(IMAGE_TYPE, subtype);
    }

    /**
     * Creates a media type with the "text" type and the given subtype.
     *
     * @throws IllegalArgumentException if subtype is invalid
     */
    static MediaType createTextType(String subtype) {
        return create(TEXT_TYPE, subtype);
    }

    /**
     * Creates a media type with the "video" type and the given subtype.
     *
     * @throws IllegalArgumentException if subtype is invalid
     */
    static MediaType createVideoType(String subtype) {
        return create(VIDEO_TYPE, subtype);
    }

    private static String normalizeToken(String token) {
        checkArgument(TOKEN_MATCHER.matchesAllOf(token));
        checkArgument(!token.isEmpty());
        return Ascii.toLowerCase(token);
    }

    private static String normalizeParameterValue(String attribute, String value) {
        checkNotNull(value); // for GWT
        checkArgument(ascii().matchesAllOf(value), "parameter values must be ASCII: %s", value);
        return CHARSET_ATTRIBUTE.equals(attribute) ? Ascii.toLowerCase(value) : value;
    }

    /**
     * Parses a media type from its string representation.
     *
     * @throws IllegalArgumentException if the input is not parsable
     */
    public static MediaType parse(String input) {
        checkNotNull(input);
        final MediaType wellKnown = KnownTypesByString.get(input);
        if (wellKnown != null) {
            return wellKnown;
        }
        Tokenizer tokenizer = new Tokenizer(input);
        try {
            String type = tokenizer.consumeToken(TOKEN_MATCHER);
            tokenizer.consumeCharacter('/');
            String subtype = tokenizer.consumeToken(TOKEN_MATCHER);
            ImmutableListMultimap.Builder<String, String> parameters = ImmutableListMultimap.builder();
            while (tokenizer.hasMore()) {
                tokenizer.consumeTokenIfPresent(LINEAR_WHITE_SPACE);
                tokenizer.consumeCharacter(';');
                tokenizer.consumeTokenIfPresent(LINEAR_WHITE_SPACE);
                String attribute = tokenizer.consumeToken(TOKEN_MATCHER);
                tokenizer.consumeCharacter('=');
                String value;
                if ('"' == tokenizer.previewChar()) {
                    tokenizer.consumeCharacter('"');
                    StringBuilder valueBuilder = new StringBuilder();
                    while ('"' != tokenizer.previewChar()) {
                        if ('\\' == tokenizer.previewChar()) {
                            tokenizer.consumeCharacter('\\');
                            valueBuilder.append(tokenizer.consumeCharacter(ascii()));
                        } else {
                            valueBuilder.append(tokenizer.consumeToken(QUOTED_TEXT_MATCHER));
                        }
                    }
                    value = valueBuilder.toString();
                    tokenizer.consumeCharacter('"');
                } else {
                    value = tokenizer.consumeToken(TOKEN_MATCHER);
                }
                parameters.put(attribute, value);
            }
            return create(type, subtype, parameters.build());
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("Could not parse '" + input + '\'', e);
        }
    }

    private static final class Tokenizer {
        final String input;
        int position;

        Tokenizer(String input) {
            this.input = input;
        }

        String consumeTokenIfPresent(CharMatcher matcher) {
            checkState(hasMore());
            int startPosition = position;
            position = matcher.negate().indexIn(input, startPosition);
            return hasMore() ? input.substring(startPosition, position) : input.substring(startPosition);
        }

        String consumeToken(CharMatcher matcher) {
            int startPosition = position;
            String token = consumeTokenIfPresent(matcher);
            checkState(position != startPosition);
            return token;
        }

        char consumeCharacter(CharMatcher matcher) {
            checkState(hasMore());
            char c = previewChar();
            checkState(matcher.matches(c));
            position++;
            return c;
        }

        char consumeCharacter(char c) {
            checkState(hasMore());
            checkState(previewChar() == c);
            position++;
            return c;
        }

        char previewChar() {
            checkState(hasMore());
            return input.charAt(position);
        }

        boolean hasMore() {
            return position >= 0 && position < input.length();
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof MediaType) {
            MediaType that = (MediaType) obj;
            return type.equals(that.type) &&
                   subtype.equals(that.subtype) &&
                   // compare parameters regardless of order
                   parametersAsMap().equals(that.parametersAsMap());
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // racy single-check idiom
        int h = hashCode;
        if (h == 0) {
            h = Objects.hashCode(type, subtype, parametersAsMap());
            hashCode = h;
        }
        return h;
    }

    private static final MapJoiner PARAMETER_JOINER = Joiner.on("; ").withKeyValueSeparator("=");

    /**
     * Returns the string representation of this media type in the format described in <a
     * href="https://datatracker.ietf.org/doc/rfc2045/">RFC 2045</a>.
     */
    @Override
    public String toString() {
        // racy single-check idiom, safe because String is immutable
        String result = toString;
        if (result == null) {
            result = computeToString();
            toString = result;
        }
        return result;
    }

    private String computeToString() {
        StringBuilder builder = new StringBuilder().append(type).append('/').append(subtype);
        if (!parameters.isEmpty()) {
            builder.append("; ");
            Multimap<String, String> quotedParameters =
                    Multimaps.transformValues(
                            parameters,
                            value -> (TOKEN_MATCHER.matchesAllOf(value) && !value.isEmpty())
                                     ? value
                                     : escapeAndQuote(value));
            PARAMETER_JOINER.appendTo(builder, quotedParameters.entries());
        }
        return builder.toString();
    }

    private static String escapeAndQuote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\r' || ch == '\\' || ch == '"') {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.append('"').toString();
    }

    /**
     * Returns {@code true} if {@code actualParameters} contains all entries of {@code expectedParameters}.
     * Note that this method does <b>not</b> require {@code actualParameters} to contain <b>only</b> the
     * entries of {@code expectedParameters}. i.e. {@code actualParameters} can contain an entry that's
     * non-existent in {@code expectedParameters}.
     */
    private static boolean containsAllParameters(Map<String, List<String>> expectedParameters,
                                                 Map<String, List<String>> actualParameters) {
        if (expectedParameters.isEmpty()) {
            return true;
        }

        for (Entry<String, List<String>> requiredEntry : expectedParameters.entrySet()) {
            final String expectedName = requiredEntry.getKey();
            final List<String> expectedValues = requiredEntry.getValue();
            if (Q.equals(expectedName)) {
                continue;
            }

            final List<String> actualValues = actualParameters.get(expectedName);

            assert !expectedValues.isEmpty();
            if (actualValues == null || actualValues.isEmpty()) {
                // Does not contain any required values.
                return false;
            }

            if (!containsAllValues(expectedValues, actualValues)) {
                return false;
            }
        }

        return true;
    }

    private static boolean containsAllValues(List<String> expectedValues, List<String> actualValues) {
        final int numRequiredValues = expectedValues.size();
        for (int i = 0; i < numRequiredValues; i++) {
            if (!containsValue(expectedValues.get(i), actualValues)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsValue(String expectedValue, List<String> actualValues) {
        final int numActualValues = actualValues.size();
        for (int i = 0; i < numActualValues; i++) {
            if (Ascii.equalsIgnoreCase(expectedValue, actualValues.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static final class KnownTypesByString {
        // Contains the well known media types as well as those registered in the server by SerializationFormats
        // to optimize parsing of these standard types.
        private static final Map<String, MediaType> KNOWN_TYPES_BY_STRING =
                Stream.concat(
                        SerializationFormat.values()
                                           .stream()
                                           .flatMap(f -> f.mediaTypes().stream()),
                        KNOWN_TYPES.keySet().stream())
                      .distinct()
                      .collect(toImmutableMap(MediaType::toString, Function.identity(), (a, b) -> a));

        @Nullable
        static MediaType get(String input) {
            return KNOWN_TYPES_BY_STRING.get(input);
        }
    }
}
