//! Uploading a file (roadmap item 67): `POST /api/tasks`, multipart, with the per-install secret -
//! the same request the browser's queue makes.

use std::io;
use std::path::Path;

use crate::Service;
use crate::events::Task;

/// The extensions an upload may carry, checked case-insensitively - the contract's list
/// (`docs/api/allowed-extensions.json`), which the service checks too. The content type is ignored,
/// as the service ignores it. A file with any other extension is refused here, before a byte of it
/// is sent.
const ALLOWED_EXTENSIONS: [&str; 19] = [
    "mp3", "wav", "flac", "ogg", "oga", "opus", "m4a", "aac", "wma", "aif", "aiff", "amr", "webm",
    "mp4", "m4v", "mov", "mkv", "mka", "3gp",
];

/// Whether `path` has an extension an upload accepts.
pub fn allowed_extension(path: &Path) -> bool {
    path.extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| {
            ALLOWED_EXTENSIONS
                .iter()
                .any(|allowed| allowed.eq_ignore_ascii_case(extension))
        })
}

/// How an upload asks for the speaker count: the task's diarization mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Speakers {
    Auto,
    Off,
    /// Exactly this many speakers (1..=20).
    Count(u32),
}

impl Speakers {
    /// The value the contract's `speakers` field takes.
    pub fn as_field(self) -> String {
        match self {
            Speakers::Auto => "auto".to_string(),
            Speakers::Off => "off".to_string(),
            Speakers::Count(count) => count.to_string(),
        }
    }
}

/// The upload's options, as the GUI's upload panel sends them: the language to transcribe in, the
/// speakers choice and the most speakers diarization may find.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadOptions {
    pub language: String,
    pub speakers: Speakers,
    pub max_speakers: u32,
}

/// Why an upload did not become a task: the message a client shows, when the service already had
/// the file the task it already is (`error.duplicate-audio.json`), and whether the connection went
/// away before an answer - which a client holds and sends again on reconnect, as the browser does.
#[derive(Debug)]
pub struct UploadError {
    message: String,
    duplicate: Option<i64>,
    lost: bool,
}

impl UploadError {
    /// The existing task, when the service answered that the file is already uploaded.
    pub fn duplicate(&self) -> Option<i64> {
        self.duplicate
    }

    /// Whether no answer came: the connection went away mid-request, so the file is worth
    /// sending again.
    pub fn lost(&self) -> bool {
        self.lost
    }
}

impl std::fmt::Display for UploadError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for UploadError {}

impl From<io::Error> for UploadError {
    fn from(error: io::Error) -> Self {
        UploadError {
            message: error.to_string(),
            duplicate: None,
            lost: false,
        }
    }
}

/// Uploads the file at `path` with `options` and returns the task the service created for it
/// (QUEUED at the end of the queue).
pub async fn upload(
    service: &Service,
    path: &Path,
    options: &UploadOptions,
) -> Result<Task, UploadError> {
    upload_with_progress(service, path, options, |_, _| {}).await
}

/// Uploads as [`upload`] does, calling `progress(sent, total)` as the file's bytes go - the file
/// is read into the request a chunk at a time, so the count is what has reached the service's
/// socket, and a large recording is never held in memory whole.
pub async fn upload_with_progress(
    service: &Service,
    path: &Path,
    options: &UploadOptions,
    mut progress: impl FnMut(u64, u64) + Send + 'static,
) -> Result<Task, UploadError> {
    if !allowed_extension(path) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{} is not an audio or video file", path.display()),
        )
        .into());
    }
    let file = tokio::fs::File::open(path)
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("{}: {error}", path.display())))?;
    let length = file
        .metadata()
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("{}: {error}", path.display())))?
        .len();
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default();
    // The file feeds a pipe the request body reads: each chunk written is counted, and dropped
    // when the request stops reading.
    let (mut feed, body) = tokio::io::duplex(64 * 1024);
    tokio::spawn(async move {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let mut file = tokio::io::BufReader::with_capacity(64 * 1024, file);
        let mut buffer = vec![0u8; 64 * 1024];
        let mut sent = 0;
        loop {
            let read = match file.read(&mut buffer).await {
                Ok(0) => break,
                Ok(read) => read,
                Err(_) => break,
            };
            sent += read as u64;
            progress(sent, length);
            if feed.write_all(&buffer[..read]).await.is_err() {
                break;
            }
        }
        let _ = feed.shutdown().await;
    });
    let part = reqwest::multipart::Part::stream_with_length(
        reqwest::Body::wrap_stream(tokio_util::io::ReaderStream::new(body)),
        length,
    )
    .file_name(name);
    let form = reqwest::multipart::Form::new()
        .part("file", part)
        .text("language", options.language.clone())
        .text("speakers", options.speakers.as_field())
        .text("maxSpeakers", options.max_speakers.to_string());
    // A request or answer that cannot travel is a lost connection: no answer came, so the file
    // is held rather than refused.
    let lost = |error: reqwest::Error| UploadError {
        message: error.to_string(),
        duplicate: None,
        lost: true,
    };
    let response = crate::client()
        .post(format!("{}/api/tasks", service.endpoint))
        .bearer_auth(&service.secret)
        .multipart(form)
        .send()
        .await
        .map_err(lost)?;
    let status = response.status();
    let body = response.text().await.map_err(lost)?;
    if status.is_success() {
        return crate::parse_json(&body).map_err(UploadError::from);
    }
    // A refusal keeps the existing task a duplicate names; any other error is its message.
    let error = crate::parse_json::<crate::ApiError>(&body).ok();
    Err(UploadError {
        message: error.as_ref().map_or_else(
            || format!("the service answered {status}"),
            |error| error.message.clone(),
        ),
        duplicate: error.and_then(|error| error.task_id),
        lost: false,
    })
}
