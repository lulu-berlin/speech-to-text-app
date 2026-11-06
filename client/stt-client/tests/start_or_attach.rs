//! Roadmap item 66, start-or-attach and the unit (test-list item 4's client half, ADR 0064). A
//! client uses the running service and starts one only when none answers, never a second: the
//! service's instance lock would make a second launch defer, but the client must not even try. The
//! launch that started the service also starts the tray; a launch that attached starts nothing.

mod support;

use std::cell::Cell;
use std::io;
use std::net::TcpListener;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use stt_client::{ServiceDirs, ensure_service, ensure_unit};

#[tokio::test]
async fn a_running_service_is_used_and_nothing_is_started() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    support::answer_forever(listener, |_| support::healthy());
    let (dir, dirs) = bootstrap(port);

    let started = Cell::new(false);
    let service = ensure_service(&dirs, || {
        started.set(true);
        Ok(())
    })
    .await
    .unwrap();

    assert_eq!(service.service.endpoint, format!("http://127.0.0.1:{port}"));
    assert!(
        !started.get(),
        "a running service must not be started again"
    );
    drop(dir);
}

#[tokio::test]
async fn with_no_service_answering_one_is_started_once_and_then_used() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = support::dirs_in(dir.path());

    let started = Cell::new(false);
    let service = ensure_service(&dirs, || {
        started.set(true);
        support::start_and_answer(dir.path());
        Ok(())
    })
    .await
    .unwrap();

    assert!(started.get());
    assert!(service.service.endpoint.starts_with("http://127.0.0.1:"));
}

// ADR 0064: the launch that started the unit starts the tray; a launch that attached to a running
// service starts nothing. So start-or-attach has to say which of the two it did.
#[tokio::test]
async fn start_or_attach_reports_whether_this_launch_started_the_service() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = support::dirs_in(dir.path());

    // Nothing answers: this launch starts the service, and must say so.
    let started = ensure_service(&dirs, || {
        support::start_and_answer(dir.path());
        Ok(())
    })
    .await
    .unwrap();
    assert!(started.started, "this launch started the service");

    // A service answers now: this launch only attaches, and must say so.
    let attached = ensure_service(&dirs, || {
        panic!("a running service must not be started again")
    })
    .await
    .unwrap();
    assert!(!attached.started, "this launch attached to the service");
    assert_eq!(attached.service.endpoint, started.service.endpoint);
}

// ADR 0064: opening any frontend brings the unit up - the service and the tray. The launch that
// started the service starts the tray, exactly once; a launch that attached to a running service
// starts no tray, since the running unit already has one.
#[tokio::test]
async fn the_launch_that_starts_the_service_starts_the_tray_and_an_attaching_launch_starts_none() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = support::dirs_in(dir.path());
    let trays = Cell::new(0);
    let start_tray = || {
        trays.set(trays.get() + 1);
        Ok(())
    };

    let first = ensure_unit(
        &dirs,
        || {
            support::start_and_answer(dir.path());
            Ok(())
        },
        start_tray,
    )
    .await
    .unwrap();
    assert!(first.started, "the first launch started the service");
    assert_eq!(
        trays.get(),
        1,
        "the launch that started the service starts the tray once"
    );

    let second = ensure_unit(
        &dirs,
        || panic!("a running service must not be started again"),
        start_tray,
    )
    .await
    .unwrap();
    assert!(!second.started, "the second launch attached");
    assert_eq!(trays.get(), 1, "a launch that attached starts no tray");
}

// ADR 0064: the unit is both up or both down. When this launch started the service and the tray
// then cannot start, the launch stops that service again and fails, naming the tray.
#[tokio::test]
async fn when_the_tray_cannot_start_the_service_this_launch_started_stops_and_the_open_fails() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = support::dirs_in(dir.path());
    let quit = Arc::new(AtomicBool::new(false));

    let error = ensure_unit(
        &dirs,
        || {
            start_and_answer_quit(dir.path(), Arc::clone(&quit));
            Ok(())
        },
        || Err(io::Error::other("no stt-tray beside stt-app")),
    )
    .await
    .unwrap_err();

    assert!(
        quit.load(Ordering::SeqCst),
        "the service this launch started must be asked to stop"
    );
    let message = error.to_string();
    assert!(message.contains("tray"), "{message}");
    assert!(message.contains("no stt-tray beside stt-app"), "{message}");
}

/// Like `support::start_and_answer`, but the stand-in also takes `POST /api/quit` the way the
/// service does (`202 Accepted`) and records it in `quit`.
fn start_and_answer_quit(dir: &std::path::Path, quit: Arc<AtomicBool>) {
    support::start_and_answer_with(dir, move |request| {
        if request.line.starts_with("POST /api/quit ") {
            quit.store(true, Ordering::SeqCst);
            ("202 Accepted", String::new())
        } else {
            support::healthy()
        }
    });
}

/// The bootstrap files a running service leaves, pointing at `port`, and the directory holding them.
fn bootstrap(port: u16) -> (tempfile::TempDir, ServiceDirs) {
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), port);
    let dirs = support::dirs_in(dir.path());
    (dir, dirs)
}
