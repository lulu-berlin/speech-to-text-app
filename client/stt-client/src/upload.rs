//! Uploading a file (roadmap item 67): `POST /api/tasks`, multipart, with the per-install secret -
//! the same request the browser's queue makes.

use std::io;
use std::path::Path;

use serde::Deserialize;

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

/// The service's error body (`error.json`).
#[derive(Deserialize)]
struct ApiError {
    message: String,
}

/// Uploads the file at `path` and returns the task the service created for it (QUEUED at the end of
/// the queue).
pub async fn upload(service: &Service, path: &Path) -> io::Result<Task> {
    if !allowed_extension(path) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{} is not an audio or video file", path.display()),
        ));
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
    let part = reqwest::multipart::Part::stream_with_length(
        reqwest::Body::wrap_stream(tokio_util::io::ReaderStream::new(file)),
        length,
    )
    .file_name(name);
    let form = reqwest::multipart::Form::new().part("file", part);
    let response = reqwest::Client::new()
        .post(format!("{}/api/tasks", service.endpoint))
        .bearer_auth(&service.secret)
        .multipart(form)
        .send()
        .await
        .map_err(io::Error::other)?;
    let status = response.status();
    let body = response.text().await.map_err(io::Error::other)?;
    if status.is_success() {
        return crate::parse_json(&body);
    }
    let message = crate::parse_json::<ApiError>(&body)
        .map(|error| error.message)
        .unwrap_or_else(|_| format!("the service answered {status}"));
    Err(io::Error::other(message))
}
