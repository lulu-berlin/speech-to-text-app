//! Roadmap item 66, test-list D10 (the client half): the terminal command the user set on the
//! Settings page is what `stt-app` uses to launch the TUI — read from `GET /api/settings` with the
//! per-install secret — and no setting means the OS launcher.

mod support;

use std::net::TcpListener;

use stt_client::{Service, terminal_command_setting};

#[test]
fn the_stored_terminal_command_is_read_from_the_service_settings() {
    for (stored, expected) in [
        (r#""wezterm start --""#, Some("wezterm start --")),
        ("null", None),
    ] {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let body = format!(r#"{{"defaultFrontend":null,"terminalCommand":{stored}}}"#);
        support::answer_forever(listener, move |_| ("200 OK", body.clone()));

        let setting = terminal_command_setting(&Service {
            endpoint: format!("http://127.0.0.1:{port}"),
            secret: "the-install-secret".into(),
        })
        .unwrap();

        assert_eq!(setting.as_deref(), expected, "stored {stored}");
    }
}
