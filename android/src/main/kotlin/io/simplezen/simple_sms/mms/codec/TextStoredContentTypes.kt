package io.simplezen.simple_sms.mms.codec

import io.simplezen.simple_sms.mms.ContentType

/**
 * The part types [PduPersister] stores as `text` rather than as data: it decodes their data as
 * UTF-8 and keeps at most 300 KiB of it (Simple Zen modification, UNFY-281). The persister, a
 * reader of a part's content and a check of what was stored all use this one set.
 */
internal val TEXT_STORED_CONTENT_TYPES: Set<String> =
    setOf(ContentType.TEXT_PLAIN, ContentType.APP_SMIL, ContentType.TEXT_HTML)
