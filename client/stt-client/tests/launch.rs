//! Roadmap item 66 (test list item 2, the browser's path; e2e line E1): `stt-app open` and the tray
//! open the browser at a one-time launch URL. The client asks the running service for a token,
//! sending the per-install secret as `Authorization: Bearer`, and opens
//! `<endpoint>/open?t=<token>` — the secret itself never appears in the URL. The service here is a
//! stand-in on a local socket that answers one request and records it.

mod support;

use std::net::TcpListener;
use std::thread;

use stt_client::{Service, launch_url};

#[tokio::test]
async fn the_client_gets_a_one_time_launch_url_from_the_service_with_its_secret_as_the_bearer() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let stand_in = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let request = support::read(&mut stream);
        support::respond(&mut stream, "200 OK", r#"{"token":"one-time"}"#);
        request
    });

    let url = launch_url(&Service {
        endpoint: format!("http://127.0.0.1:{port}"),
        secret: "the-install-secret".into(),
    })
    .await
    .unwrap();

    assert_eq!(url, format!("http://127.0.0.1:{port}/open?t=one-time"));
    let request = stand_in.join().unwrap();
    assert!(
        request.line.starts_with("POST /api/launch-token "),
        "{:?}",
        request.head
    );
    assert_eq!(
        request.header("Authorization"),
        Some("Bearer the-install-secret"),
        "{:?}",
        request.head
    );
}
