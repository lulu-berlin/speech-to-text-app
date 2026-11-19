//! Roadmap item 67, the review (R3): a download that fails after its path was accepted - the
//! service answers an error, or the connection drops before the body - leaves a file already at
//! that path as it was. (A path that cannot be written is still reported before anything is
//! fetched: `download.rs`.) The second review (S3): a download that succeeds over a file keeps that
//! file's permissions.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{OpenTranscript, open_transcript};

const ERROR: &str = include_str!("../../../docs/api/error.json");
const WAIT: Duration = Duration::from_secs(20);
/// What the user's file held before the downloads.
const EARLIER: &str = "1\n00:00:00,000 --> 00:00:02,100\nThe user's own transcript.\n";

/// One way a download fails: the service's answer - `None` drops the connection before any - and
/// how its notice is told apart from the one before.
struct Failure {
    status: &'static str,
    body: Option<&'static str>,
    told: fn(&str) -> bool,
}

#[test]
fn a_failed_download_leaves_the_file_at_its_path_as_it_was() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();
    let files = tempfile::tempdir().unwrap();
    let target = files.path().join("memo.txt");
    fs::write(&target, EARLIER).unwrap();

    // Three downloads to the file, each failing its own way; the notice tells them apart.
    let failures = [
        Failure {
            status: "404 Not Found",
            body: Some(ERROR),
            told: |text| text.contains("404"),
        },
        Failure {
            status: "500 Internal Server Error",
            body: Some(ERROR),
            told: |text| text.contains("500"),
        },
        Failure {
            status: "",
            body: None,
            told: |text| !text.contains("500"),
        },
    ];
    for Failure { status, body, told } in failures {
        tui.press(b"d");
        tui.wait_for(WAIT, |text| {
            text.contains("Download") && text.contains("txt")
        });
        tui.press(target.to_str().unwrap().as_bytes());
        tui.press(b"\r");
        let fetch = service.next_get_from(&tui, WAIT);
        assert!(
            fetch
                .request()
                .line
                .starts_with("GET /api/tasks/3/transcript?format=txt "),
            "{:?}",
            fetch.request().line
        );
        match body {
            Some(body) => fetch.answer(status, body),
            None => drop(fetch),
        }
        let screen = tui.wait_for(WAIT, |text| text.contains("download failed") && told(text));
        assert_eq!(
            fs::read_to_string(&target).unwrap(),
            EARLIER,
            "a download that failed ({status:?}) leaves the file as it was:\n{screen}"
        );
    }
}

/// The second review (S3): a download that replaces a file keeps that file's permissions - a
/// transcript the user kept private stays private.
#[cfg(unix)]
#[test]
fn a_download_over_a_file_keeps_its_permissions() {
    use std::os::unix::fs::PermissionsExt;

    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();
    let files = tempfile::tempdir().unwrap();
    let target = files.path().join("memo.txt");
    fs::write(&target, EARLIER).unwrap();
    fs::set_permissions(&target, fs::Permissions::from_mode(0o600)).unwrap();

    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("txt")
    });
    tui.press(target.to_str().unwrap().as_bytes());
    tui.press(b"\r");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", "The new transcript.\n");
    tui.wait_for(WAIT, |text| text.contains("saved"));

    assert_eq!(
        fs::read_to_string(&target).unwrap(),
        "The new transcript.\n"
    );
    assert_eq!(
        fs::metadata(&target).unwrap().permissions().mode() & 0o777,
        0o600,
        "the replaced file's permissions stay"
    );
}
