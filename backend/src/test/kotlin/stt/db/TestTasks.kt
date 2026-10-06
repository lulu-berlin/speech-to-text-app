package stt.db

/** A freshly queued task: every nullable field null, schema defaults for the rest. */
internal fun queuedTask(
    filename: String,
    createdAt: Long,
) = Task(
    originalFilename = filename,
    contentType = "audio/wav",
    durationMs = null,
    status = TaskStatus.QUEUED,
    phase = null,
    queuePosition = null,
    language = "en",
    speakersMode = SpeakersMode.AUTO,
    speakersCount = null,
    maxSpeakers = 8,
    model = "large-v3-turbo-q5_0",
    progress = 0.0,
    error = null,
    createdAt = createdAt,
    startedAt = null,
    finishedAt = null,
)
