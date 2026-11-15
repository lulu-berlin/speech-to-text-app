//! Roadmap item 67: an upload reports its progress as the file's bytes go - not only at the end -
//! which is what a queue's per-file upload line shows.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use stt_client::Service;
use stt_client::{Speakers, UploadOptions, upload_with_progress};

/// A service that reads a `POST /api/tasks` body in 8 KiB pieces, pausing between them, then
/// answers 201 with the task it was sent.
fn slow_service() -> (Service, Arc<Mutex<Vec<String>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let seen = Arc::new(Mutex::new(Vec::new()));
    let log = Arc::clone(&seen);
    std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        // The headers, up to the blank line.
        let mut headers = Vec::new();
        let mut byte = [0u8; 1];
        while !headers.ends_with(b"\r\n\r\n") {
            stream.read_exact(&mut byte).unwrap();
            headers.push(byte[0]);
        }
        let headers = String::from_utf8_lossy(&headers).into_owned();
        let length: usize = headers
            .lines()
            .find_map(|line| line.strip_prefix("content-length: "))
            .unwrap()
            .trim()
            .parse()
            .unwrap();
        // The body, slowly, in pieces - so the client's writes are paced by this reader.
        let mut body = Vec::new();
        let mut piece = vec![0u8; 8 * 1024];
        while body.len() < length {
            let want = piece.len().min(length - body.len());
            stream.read_exact(&mut piece[..want]).unwrap();
            body.extend_from_slice(&piece[..want]);
            log.lock().unwrap().push(format!("{}", body.len()));
            std::thread::sleep(Duration::from_millis(15));
        }
        let task = r#"{"id":9,"filename":"a.wav","status":"QUEUED","transcriptionProgress":0.0}"#;
        write!(
            stream,
            "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
            task.len(),
            task
        )
        .unwrap();
    });
    (
        Service {
            endpoint: format!("http://127.0.0.1:{port}"),
            secret: "the-install-secret".to_string(),
        },
        seen,
    )
}

#[tokio::test]
async fn an_upload_reports_its_progress_as_the_bytes_go() {
    let (service, _seen) = slow_service();
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("a.wav");
    // Larger than the client's pipe, so the count has several steps to take.
    std::fs::write(&path, vec![7u8; 256 * 1024]).unwrap();
    let total = std::fs::metadata(&path).unwrap().len();
    let seen = Arc::new(Mutex::new(Vec::new()));
    let log = Arc::clone(&seen);
    let task = upload_with_progress(
        &service,
        &path,
        &UploadOptions {
            language: "en".to_string(),
            speakers: Speakers::Auto,
            max_speakers: 8,
        },
        move |sent, total| log.lock().unwrap().push((sent, total)),
    )
    .await
    .unwrap();
    assert_eq!(task.id, 9);
    let seen = seen.lock().unwrap().clone();
    assert!(seen.len() > 1, "more than one step: {seen:?}");
    assert!(
        seen.windows(2).all(|pair| pair[0].0 < pair[1].0),
        "each step moves the count on: {seen:?}"
    );
    assert!(seen.iter().any(|(sent, _)| *sent < total), "{seen:?}");
    assert_eq!(seen.last().unwrap().0, total, "{seen:?}");
}
