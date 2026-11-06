//! Roadmap item 67: the service is loopback-only, so the client reaches it directly even when the
//! environment sets an HTTP proxy. The per-install secret must never go to a proxy, and a bogus
//! proxy must not make the service unreachable.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_proxy_in_the_environment_never_takes_the_request() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    // A proxy on a closed port: anything routed through it fails.
    let tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        120,
        24,
        &[
            ("HTTP_PROXY", "http://127.0.0.1:1"),
            ("HTTPS_PROXY", "http://127.0.0.1:1"),
            ("ALL_PROXY", "http://127.0.0.1:1"),
        ],
    );
    let mut stream = service.next_stream(WAIT);
    assert_eq!(
        stream.authorization.as_deref(),
        Some("Bearer the-install-secret"),
        "the event stream reaches the service directly, with the credential"
    );
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
}
