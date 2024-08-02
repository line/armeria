/*
 *  Copyright 2020 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License; charset=utf-8"; you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * String constants defined in {@link MediaType} class.
 */
public final class MediaTypeNames {

    /**
     * {@value #ANY_TYPE}.
     */
    public static final String ANY_TYPE = "*/*";
    /**
     * {@value #ANY_TEXT_TYPE}.
     */
    public static final String ANY_TEXT_TYPE = "text/*";
    /**
     * {@value #ANY_IMAGE_TYPE}.
     */
    public static final String ANY_IMAGE_TYPE = "image/*";
    /**
     * {@value #ANY_AUDIO_TYPE}.
     */
    public static final String ANY_AUDIO_TYPE = "audio/*";
    /**
     * {@value #ANY_VIDEO_TYPE}.
     */
    public static final String ANY_VIDEO_TYPE = "video/*";

    /**
     * {@value #ANY_FONT_TYPE}.
     */
    public static final String ANY_FONT_TYPE = "font/*";

    /**
     * {@value #ANY_APPLICATION_TYPE}.
     */
    public static final String ANY_APPLICATION_TYPE = "application/*";
    /**
     * {@value #ANY_MULTIPART_TYPE}.
     */
    public static final String ANY_MULTIPART_TYPE = "multipart/*";

    /* text types */

    /**
     * {@value #CACHE_MANIFEST_UTF_8}.
     */
    public static final String CACHE_MANIFEST_UTF_8 = "text/cache-manifest; charset=utf-8";
    /**
     * {@value #CSS_UTF_8}.
     */
    public static final String CSS_UTF_8 = "text/css; charset=utf-8";
    /**
     * {@value #CSV_UTF_8}.
     */
    public static final String CSV_UTF_8 = "text/csv; charset=utf-8";
    /**
     * {@value #HTML_UTF_8}.
     */
    public static final String HTML_UTF_8 = "text/html; charset=utf-8";
    /**
     * {@value #I_CALENDAR_UTF_8}.
     */
    public static final String I_CALENDAR_UTF_8 = "text/calendar; charset=utf-8";
    /**
     * {@value #PLAIN_TEXT}.
     */
    public static final String PLAIN_TEXT = "text/plain";
    /**
     * {@value #PLAIN_TEXT_UTF_8}.
     */
    public static final String PLAIN_TEXT_UTF_8 = "text/plain; charset=utf-8";
    /**
     * {@value #EVENT_STREAM}.
     */
    public static final String EVENT_STREAM = "text/event-stream";
    /**
     * {@value #TEXT_JAVASCRIPT_UTF_8}.
     */
    public static final String TEXT_JAVASCRIPT_UTF_8 = "text/javascript; charset=utf-8";
    /**
     * {@value #TSV_UTF_8}.
     */
    public static final String TSV_UTF_8 = "text/tab-separated-values; charset=utf-8";
    /**
     * {@value #VCARD_UTF_8}.
     */
    public static final String VCARD_UTF_8 = "text/vcard; charset=utf-8";
    /**
     * {@value #WML_UTF_8}.
     */
    public static final String WML_UTF_8 = "text/vnd.wap.wml; charset=utf-8";
    /**
     * {@value #XML_UTF_8}.
     */
    public static final String XML_UTF_8 = "text/xml; charset=utf-8";
    /**
     * {@value #VTT_UTF_8}.
     */
    public static final String VTT_UTF_8 = "text/vtt; charset=utf-8";

    /* image types */

    /**
     * {@value #BMP}.
     */
    public static final String BMP = "image/bmp";
    /**
     * {@value #CRW}.
     */
    public static final String CRW = "image/x-canon-crw";
    /**
     * {@value #GIF}.
     */
    public static final String GIF = "image/gif";
    /**
     * {@value #ICO}.
     */
    public static final String ICO = "image/vnd.microsoft.icon";
    /**
     * {@value #JPEG}.
     */
    public static final String JPEG = "image/jpeg";
    /**
     * {@value #PNG}.
     */
    public static final String PNG = "image/png";
    /**
     * {@value #PSD}.
     */
    public static final String PSD = "image/vnd.adobe.photoshop";
    /**
     * {@value #SVG_UTF_8}.
     */
    public static final String SVG_UTF_8 = "image/svg+xml; charset=utf-8";
    /**
     * {@value #TIFF}.
     */
    public static final String TIFF = "image/tiff";
    /**
     * {@value #WEBP}.
     */
    public static final String WEBP = "image/webp";

    /**
     * {@value #HEIF}.
     */
    public static final String HEIF = "image/heif";

    /**
     * {@value #JP2K}.
     */
    public static final String JP2K = "image/jp2";

    /**
     * {@value #AVIF}.
     */
    public static final String AVIF = "image/avif";

    /* audio types */

    /**
     * {@value #MP4_AUDIO}.
     */
    public static final String MP4_AUDIO = "audio/mp4";
    /**
     * {@value #MPEG_AUDIO}.
     */
    public static final String MPEG_AUDIO = "audio/mpeg";
    /**
     * {@value #OGG_AUDIO}.
     */
    public static final String OGG_AUDIO = "audio/ogg";
    /**
     * {@value #WEBM_AUDIO}.
     */
    public static final String WEBM_AUDIO = "audio/webm";
    /**
     * {@value #L16_AUDIO}.
     */
    public static final String L16_AUDIO = "audio/l16";
    /**
     * {@value #L24_AUDIO}.
     */
    public static final String L24_AUDIO = "audio/l24";
    /**
     * {@value #BASIC_AUDIO}.
     */
    public static final String BASIC_AUDIO = "audio/basic";
    /**
     * {@value #AAC_AUDIO}.
     */
    public static final String AAC_AUDIO = "audio/aac";
    /**
     * {@value #VORBIS_AUDIO}.
     */
    public static final String VORBIS_AUDIO = "audio/vorbis";
    /**
     * {@value #WMA_AUDIO}.
     */
    public static final String WMA_AUDIO = "audio/x-ms-wma";
    /**
     * {@value #WAX_AUDIO}.
     */
    public static final String WAX_AUDIO = "audio/x-ms-wax";
    /**
     * {@value #VND_REAL_AUDIO}.
     */
    public static final String VND_REAL_AUDIO = "audio/vnd.rn-realaudio";
    /**
     * {@value #VND_WAVE_AUDIO}.
     */
    public static final String VND_WAVE_AUDIO = "audio/vnd.wave";

    /* video types */

    /**
     * {@value #MP4_VIDEO}.
     */
    public static final String MP4_VIDEO = "video/mp4";
    /**
     * {@value #MPEG_VIDEO}.
     */
    public static final String MPEG_VIDEO = "video/mpeg";
    /**
     * {@value #OGG_VIDEO}.
     */
    public static final String OGG_VIDEO = "video/ogg";
    /**
     * {@value #QUICKTIME}.
     */
    public static final String QUICKTIME = "video/quicktime";
    /**
     * {@value #WEBM_VIDEO}.
     */
    public static final String WEBM_VIDEO = "video/webm";
    /**
     * {@value #WMV}.
     */
    public static final String WMV = "video/x-ms-wmv";
    /**
     * {@value #FLV_VIDEO}.
     */
    public static final String FLV_VIDEO = "video/x-flv";
    /**
     * {@value #THREE_GPP_VIDEO}.
     */
    public static final String THREE_GPP_VIDEO = "video/3gpp";
    /**
     * {@value #THREE_GPP2_VIDEO}.
     */
    public static final String THREE_GPP2_VIDEO = "video/3gpp2";

    /* application types */

    /**
     * {@value #APPLICATION_XML_UTF_8}.
     */
    public static final String APPLICATION_XML_UTF_8 = "application/xml; charset=utf-8";
    /**
     * {@value #ATOM_UTF_8}.
     */
    public static final String ATOM_UTF_8 = "application/atom+xml; charset=utf-8";
    /**
     * {@value #BZIP2}.
     */
    public static final String BZIP2 = "application/x-bzip2";
    /**
     * {@value #DART_UTF_8}.
     */
    public static final String DART_UTF_8 = "application/dart; charset=utf-8";
    /**
     * {@value #APPLE_PASSBOOK}.
     */
    public static final String APPLE_PASSBOOK = "application/vnd.apple.pkpass";
    /**
     * {@value #EOT}.
     */
    public static final String EOT = "application/vnd.ms-fontobject";
    /**
     * {@value #EPUB}.
     */
    public static final String EPUB = "application/epub+zip";
    /**
     * {@value #FORM_DATA}.
     */
    public static final String FORM_DATA = "application/x-www-form-urlencoded";
    /**
     * {@value #MULTIPART_ALTERNATIVE}.
     */
    public static final String MULTIPART_ALTERNATIVE = "multipart/alternative";
    /**
     * {@value #MULTIPART_DIGEST}.
     */
    public static final String MULTIPART_DIGEST = "multipart/digest";
    /**
     * {@value #MULTIPART_ENCRYPTED}.
     */
    public static final String MULTIPART_ENCRYPTED = "multipart/encrypted";
    /**
     * {@value #MULTIPART_FORM_DATA}.
     */
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    /**
     * {@value #MULTIPART_MIXED}.
     */
    public static final String MULTIPART_MIXED = "multipart/mixed";
    /**
     * {@value #MULTIPART_PARALLEL}.
     */
    public static final String MULTIPART_PARALLEL = "multipart/parallel";
    /**
     * {@value #MULTIPART_RELATED}.
     */
    public static final String MULTIPART_RELATED = "multipart/related";
    /**
     * {@value #MULTIPART_SIGNED}.
     */
    public static final String MULTIPART_SIGNED = "multipart/signed";
    /**
     * {@value #KEY_ARCHIVE}.
     */
    public static final String KEY_ARCHIVE = "application/pkcs12";
    /**
     * {@value #APPLICATION_BINARY}.
     */
    public static final String APPLICATION_BINARY = "application/binary";
    /**
     * {@value #GEO_JSON}.
     */
    public static final String GEO_JSON = "application/geo+json";
    /**
     * {@value #GIT_UPLOAD_PACK_ADVERTISEMENT}.
     */
    @UnstableApi
    public static final String GIT_UPLOAD_PACK_ADVERTISEMENT = "application/x-git-upload-pack-advertisement";
    /**
     * {@value #GIT_UPLOAD_PACK_REQUEST}.
     */
    @UnstableApi
    public static final String GIT_UPLOAD_PACK_REQUEST = "application/x-git-upload-pack-request";
    /**
     * {@value #GIT_UPLOAD_PACK_RESULT}.
     */
    @UnstableApi
    public static final String GIT_UPLOAD_PACK_RESULT = "application/x-git-upload-pack-result";
    /**
     * {@value #GZIP}.
     */
    public static final String GZIP = "application/x-gzip";
    /**
     * {@value #BROTLI}.
     */
    public static final String BROTLI = "application/brotli";
    /**
     * {@value #ZSTD}.
     */
    public static final String ZSTD = "application/zstd";
    /**
     * {@value #HAL_JSON}.
     */
    public static final String HAL_JSON = "application/hal+json";
    /**
     * {@value #JAVASCRIPT_UTF_8}.
     */
    public static final String JAVASCRIPT_UTF_8 = "application/javascript; charset=utf-8";
    /**
     * {@value #JOSE}.
     */
    public static final String JOSE = "application/jose";
    /**
     * {@value #JOSE_JSON}.
     */
    public static final String JOSE_JSON = "application/jose+json";
    /**
     * {@value #JSON_UTF_8}.
     */
    public static final String JSON_UTF_8 = "application/json; charset=utf-8";
    /**
     * {@value #JSON}.
     */
    public static final String JSON = "application/json";
    /**
     * {@value #JSON_PATCH}.
     */
    public static final String JSON_PATCH = "application/json-patch+json";
    /**
     * {@value #JSON_SEQ}.
     */
    public static final String JSON_SEQ = "application/json-seq";
    /**
     * {@value #JSON_LINES}.
     */
    public static final String JSON_LINES = "application/x-ndjson";
    /**
     * {@value #JSON}.
     */
    public static final String JWT = "application/jwt";
    /**
     * {@value #MANIFEST_JSON_UTF_8}.
     */
    public static final String MANIFEST_JSON_UTF_8 = "application/manifest+json; charset=utf-8";
    /**
     * {@value #KML}.
     */
    public static final String KML = "application/vnd.google-earth.kml+xml";
    /**
     * {@value #KMZ}.
     */
    public static final String KMZ = "application/vnd.google-earth.kmz";
    /**
     * {@value #MBOX}.
     */
    public static final String MBOX = "application/mbox";
    /**
     * {@value #APPLE_MOBILE_CONFIG}.
     */
    public static final String APPLE_MOBILE_CONFIG = "application/x-apple-aspen-config";
    /**
     * {@value #MICROSOFT_EXCEL}.
     */
    public static final String MICROSOFT_EXCEL = "application/vnd.ms-excel";
    /**
     * {@value #MICROSOFT_OUTLOOK}.
     */
    public static final String MICROSOFT_OUTLOOK = "application/vnd.ms-outlook";
    /**
     * {@value #MICROSOFT_POWERPOINT}.
     */
    public static final String MICROSOFT_POWERPOINT = "application/vnd.ms-powerpoint";
    /**
     * {@value #MICROSOFT_WORD}.
     */
    public static final String MICROSOFT_WORD = "application/msword";

    /**
     * {@value #MEDIA_PRESENTATION_DESCRIPTION}.
     */
    public static final String MEDIA_PRESENTATION_DESCRIPTION = "application/dash+xml";

    /**
     * {@value #WASM_APPLICATION}.
     */
    public static final String WASM_APPLICATION = "application/wasm";
    /**
     * {@value #NACL_APPLICATION}.
     */
    public static final String NACL_APPLICATION = "application/x-nacl";
    /**
     * {@value #NACL_PORTABLE_APPLICATION}.
     */
    public static final String NACL_PORTABLE_APPLICATION = "application/x-pnacl";
    /**
     * {@value #OCTET_STREAM}.
     */
    public static final String OCTET_STREAM = "application/octet-stream";
    /**
     * {@value #OGG_CONTAINER}.
     */
    public static final String OGG_CONTAINER = "application/ogg";
    /**
     * {@value #OOXML_DOCUMENT}.
     */
    public static final String OOXML_DOCUMENT =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    /**
     * {@value #OOXML_PRESENTATION}.
     */
    public static final String OOXML_PRESENTATION =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    /**
     * {@value #OOXML_SHEET}.
     */
    public static final String OOXML_SHEET =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /**
     * {@value #OPENDOCUMENT_GRAPHICS}.
     */
    public static final String OPENDOCUMENT_GRAPHICS = "application/vnd.oasis.opendocument.graphics";
    /**
     * {@value #OPENDOCUMENT_PRESENTATION}.
     */
    public static final String OPENDOCUMENT_PRESENTATION = "application/vnd.oasis.opendocument.presentation";
    /**
     * {@value #OPENDOCUMENT_SPREADSHEET}.
     */
    public static final String OPENDOCUMENT_SPREADSHEET = "application/vnd.oasis.opendocument.spreadsheet";
    /**
     * {@value #OPENDOCUMENT_TEXT}.
     */
    public static final String OPENDOCUMENT_TEXT = "application/vnd.oasis.opendocument.text";

    /**
     * {@value #OPENSEARCH_DESCRIPTION_UTF_8}.
     */
    public static final String OPENSEARCH_DESCRIPTION_UTF_8 =
            "application/opensearchdescription+xml; charset=utf-8";

    /**
     * {@value #PDF}.
     */
    public static final String PDF = "application/pdf";
    /**
     * {@value #POSTSCRIPT}.
     */
    public static final String POSTSCRIPT = "application/postscript";
    /**
     * {@value #PROTOBUF}.
     */
    public static final String PROTOBUF = "application/protobuf";
    /**
     * {@value #X_PROTOBUF}.
     */
    public static final String X_PROTOBUF = "application/x-protobuf";
    /**
     * {@value #X_GOOGLE_PROTOBUF}.
     */
    public static final String X_GOOGLE_PROTOBUF = "application/x-google-protobuf";
    /**
     * {@value #RDF_XML_UTF_8}.
     */
    public static final String RDF_XML_UTF_8 = "application/rdf+xml; charset=utf-8";
    /**
     * {@value #RTF_UTF_8}.
     */
    public static final String RTF_UTF_8 = "application/rtf; charset=utf-8";
    /**
     * {@value #SFNT}.
     */
    public static final String SFNT = "application/font-sfnt";
    /**
     * {@value #SHOCKWAVE_FLASH}.
     */
    public static final String SHOCKWAVE_FLASH = "application/x-shockwave-flash";
    /**
     * {@value #SKETCHUP}.
     */
    public static final String SKETCHUP = "application/vnd.sketchup.skp";
    /**
     * {@value #SOAP_XML_UTF_8}.
     */
    public static final String SOAP_XML_UTF_8 = "application/soap+xml; charset=utf-8";
    /**
     * {@value #TAR}.
     */
    public static final String TAR = "application/x-tar";
    /**
     * {@value #WOFF}.
     */
    public static final String WOFF = "application/font-woff";
    /**
     * {@value #WOFF2}.
     */
    public static final String WOFF2 = "application/font-woff2";
    /**
     * {@value #XHTML_UTF_8}.
     */
    public static final String XHTML_UTF_8 = "application/xhtml+xml; charset=utf-8";
    /**
     * {@value #XRD_UTF_8}.
     */
    public static final String XRD_UTF_8 = "application/xrd+xml; charset=utf-8";
    /**
     * {@value #ZIP}.
     */
    public static final String ZIP = "application/zip";

    /* font types */

    /**
     * {@value #FONT_COLLECTION}.
     */
    public static final String FONT_COLLECTION = "font/collection";

    /**
     * {@value #FONT_OTF}.
     */
    public static final String FONT_OTF = "font/otf";

    /**
     * {@value #FONT_SFNT}.
     */
    public static final String FONT_SFNT = "font/sfnt";

    /**
     * {@value #FONT_TTF}.
     */
    public static final String FONT_TTF = "font/ttf";

    /**
     * {@value #FONT_WOFF}.
     */
    public static final String FONT_WOFF = "font/woff";

    /**
     * {@value #FONT_WOFF2}.
     */
    public static final String FONT_WOFF2 = "font/woff2";

    /* GraphQL types */

    /**
     * {@value #GRAPHQL}.
     */
    public static final String GRAPHQL = "application/graphql";
    /**
     * {@value #GRAPHQL_JSON}.
     */
    public static final String GRAPHQL_JSON = "application/graphql+json";
    /**
     * {@value #GRAPHQL_JSON}.
     */
    public static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";

    private MediaTypeNames() {}
}
