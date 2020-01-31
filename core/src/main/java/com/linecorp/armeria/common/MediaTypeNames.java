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

public final class MediaTypeNames {

    public static final String ANY_TYPE = "*/*";
    public static final String ANY_TEXT_TYPE = "text/*";
    public static final String ANY_IMAGE_TYPE = "image/*";
    public static final String ANY_AUDIO_TYPE = "audio/*";
    public static final String ANY_VIDEO_TYPE = "video/*";
    public static final String ANY_APPLICATION_TYPE = "application/*";

    public static final String CACHE_MANIFEST_UTF_8 = "text/cache-manifest; charset=utf-8";
    public static final String CSS_UTF_8 = "text/css; charset=utf-8";
    public static final String CSV_UTF_8 = "text/csv; charset=utf-8";
    public static final String HTML_UTF_8 = "text/html; charset=utf-8";
    public static final String I_CALENDAR_UTF_8 = "text/calendar; charset=utf-8";
    public static final String PLAIN_TEXT_UTF_8 = "text/plain; charset=utf-8";
    public static final String EVENT_STREAM = "text/event-stream";
    public static final String TEXT_JAVASCRIPT_UTF_8 = "text/javascript; charset=utf-8";
    public static final String TSV_UTF_8 = "text/tab-separated-values; charset=utf-8";
    public static final String VCARD_UTF_8 = "text/vcard; charset=utf-8";
    public static final String WML_UTF_8 = "text/vnd.wap.wml; charset=utf-8";
    public static final String XML_UTF_8 = "text/xml; charset=utf-8";
    public static final String VTT_UTF_8 = "text/vtt; charset=utf-8";

    public static final String BMP = "image/bmp";
    public static final String CRW = "image/x-canon-crw";
    public static final String GIF = "image/gif";
    public static final String ICO = "image/vnd.microsoft.icon";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    public static final String PSD = "image/vnd.adobe.photoshop";
    public static final String SVG_UTF_8 = "image/svg+xml; charset=utf-8";
    public static final String TIFF = "image/tiff";
    public static final String WEBP = "image/webp";

    /* audio types */
    public static final String MP4_AUDIO = "audio/mp4";
    public static final String MPEG_AUDIO = "audio/mpeg";
    public static final String OGG_AUDIO = "audio/ogg";
    public static final String WEBM_AUDIO = "audio/webm";
    public static final String L16_AUDIO = "audio/l16";
    public static final String L24_AUDIO = "audio/l24";
    public static final String BASIC_AUDIO = "audio/basic";
    public static final String AAC_AUDIO = "audio/aac";
    public static final String VORBIS_AUDIO = "audio/vorbis";
    public static final String WMA_AUDIO = "audio/x-ms-wma";
    public static final String WAX_AUDIO = "audio/x-ms-wax";
    public static final String VND_REAL_AUDIO = "audio/vnd.rn-realaudio";
    public static final String VND_WAVE_AUDIO = "audio/vnd.wave";

    /* video types */
    public static final String MP4_VIDEO = "video/mp4";
    public static final String MPEG_VIDEO = "video/mpeg";
    public static final String OGG_VIDEO = "video/ogg";
    public static final String QUICKTIME = "video/quicktime";
    public static final String WEBM_VIDEO = "video/webm";
    public static final String WMV = "video/x-ms-wmv";
    public static final String FLV_VIDEO = "video/x-flv";
    public static final String THREE_GPP_VIDEO = "video/3gpp";
    public static final String THREE_GPP2_VIDEO = "video/3gpp2";

    /* application types */
    public static final String APPLICATION_XML_UTF_8 = "application/xml; charset=utf-8";
    public static final String ATOM_UTF_8 = "application/atom+xml; charset=utf-8";
    public static final String BZIP2 = "application/x-bzip2";
    public static final String DART_UTF_8 = "application/dart; charset=utf-8";

    public static final String APPLE_PASSBOOK = "application/vnd.apple.pkpass";

    public static final String EOT = "application/vnd.ms-fontobject";

    public static final String EPUB = "application/epub+zip";
    public static final String FORM_DATA = "application/x-www-form-urlencoded";
    public static final String KEY_ARCHIVE = "application/pkcs12";
    public static final String APPLICATION_BINARY = "application/binary";

    public static final String GEO_JSON = "application/geo+json";

    public static final String GZIP = "application/x-gzip";

    public static final String HAL_JSON = "application/hal+json";
    public static final String JAVASCRIPT_UTF_8 = "application/javascript; charset=utf-8";
    public static final String JOSE = "application/jose";
    public static final String JOSE_JSON = "application/jose+json";
    public static final String JSON_UTF_8 = "application/json; charset=utf-8";
    public static final String JSON = "application/json";
    public static final String JSON_PATCH = "application/json-patch+json";
    public static final String JSON_SEQ = "application/json-seq";
    public static final String MANIFEST_JSON_UTF_8 = "application/manifest+json; charset=utf-8";

    public static final String KML = "application/vnd.google-earth.kml+xml";
    public static final String KMZ = "application/vnd.google-earth.kmz";
    public static final String MBOX = "application/mbox";

    public static final String APPLE_MOBILE_CONFIG = "application/x-apple-aspen-config";

    public static final String MICROSOFT_EXCEL = "application/vnd.ms-excel";
    public static final String MICROSOFT_OUTLOOK = "application/vnd.ms-outlook";
    public static final String MICROSOFT_POWERPOINT = "application/vnd.ms-powerpoint";
    public static final String MICROSOFT_WORD = "application/msword";

    public static final String WASM_APPLICATION = "application/wasm";
    public static final String NACL_APPLICATION = "application/x-nacl";
    public static final String NACL_PORTABLE_APPLICATION = "application/x-pnacl";

    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String OGG_CONTAINER = "application/ogg";

    public static final String OOXML_DOCUMENT =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String OOXML_PRESENTATION =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    public static final String OOXML_SHEET =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String OPENDOCUMENT_GRAPHICS = "application/vnd.oasis.opendocument.graphics";
    public static final String OPENDOCUMENT_PRESENTATION = "application/vnd.oasis.opendocument.presentation";
    public static final String OPENDOCUMENT_SPREADSHEET = "application/vnd.oasis.opendocument.spreadsheet";
    public static final String OPENDOCUMENT_TEXT = "application/vnd.oasis.opendocument.text";

    public static final String PDF = "application/pdf";
    public static final String POSTSCRIPT = "application/postscript";
    public static final String PROTOBUF = "application/protobuf";
    public static final String RDF_XML_UTF_8 = "application/rdf+xml; charset=utf-8";
    public static final String RTF_UTF_8 = "application/rtf; charset=utf-8";
    public static final String SFNT = "application/font-sfnt";
    public static final String SHOCKWAVE_FLASH = "application/x-shockwave-flash";
    public static final String SKETCHUP = "application/vnd.sketchup.skp";
    public static final String SOAP_XML_UTF_8 = "application/soap+xml; charset=utf-8";
    public static final String TAR = "application/x-tar";
    public static final String WOFF = "application/font-woff";
    public static final String WOFF2 = "application/font-woff2";
    public static final String XHTML_UTF_8 = "application/xhtml+xml; charset=utf-8";
    public static final String XRD_UTF_8 = "application/xrd+xml; charset=utf-8";
    public static final String ZIP = "application/zip";

    private MediaTypeNames() {}
}
