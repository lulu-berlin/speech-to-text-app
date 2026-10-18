package stt.db

/**
 * An upload outgrew its size limit while it was being staged (item 41). The staged original is already discarded
 * when this is thrown; the upload route answers `413 upload-too-large`.
 */
internal class UploadTooLargeException : Exception()
