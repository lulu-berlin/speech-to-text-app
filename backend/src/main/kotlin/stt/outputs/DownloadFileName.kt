package stt.outputs

/**
 * The download file name for a transcript: the [originalFilename]'s stem plus [extension]
 * (`Path(audio).stem`; `interview.mp3` → `interview.srt`). Only the last suffix is stripped; a leading dot
 * (`.hidden`) is not a suffix.
 */
internal fun downloadFileName(
    originalFilename: String,
    extension: String,
): String {
    val dot = originalFilename.lastIndexOf('.')
    val stem = if (dot > 0) originalFilename.substring(0, dot) else originalFilename
    return "$stem.$extension"
}

/**
 * The `Content-Disposition` value that offers [fileName] as a download (RFC 6266). A header carries no UTF-8, so
 * the name goes twice: `filename*` holds it exactly, as RFC 5987's `UTF-8''` percent-encoding, which browsers prefer;
 * `filename` holds an ASCII fallback for those that do not read it — every non-ASCII character, `"` and `\` as `_`,
 * so the quoted string never breaks (item 76: `محاضرة الأحد.txt` was saved as `transcript.txt`).
 */
internal fun attachmentDisposition(fileName: String): String {
    val ascii = fileName.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded =
        fileName.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char in RFC5987_ATTR_CHARS) char.toString() else "%%%02X".format(code)
        }
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

// RFC 5987's attr-char: the bytes `filename*` carries as they are; every other byte is percent-encoded.
private val RFC5987_ATTR_CHARS: Set<Char> =
    (('A'..'Z') + ('a'..'z') + ('0'..'9') + "!#$&+-.^_`|~".toList()).toSet()
