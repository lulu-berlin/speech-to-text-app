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
