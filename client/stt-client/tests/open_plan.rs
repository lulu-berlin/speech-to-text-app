//! Roadmap item 66: one description of what opening a frontend runs, shared by `stt-app` and the
//! tray. A terminal frontend goes through the OS terminal chain, a GUI one runs its own command,
//! and only a browser frontend needs the running service (its one-time launch URL).

mod support;

use std::net::TcpListener;

use stt_client::{
    Descriptor, Launch, LaunchCommand, OpenPlan, Os, Service, TerminalSettings, open_plan,
};

#[test]
fn a_terminal_frontend_becomes_the_os_terminal_command_and_needs_no_service() {
    let descriptor = Descriptor {
        version: 1,
        id: "tui".into(),
        name: "Terminal TUI".into(),
        launch: Launch::Terminal {
            command: "stt-tui".into(),
            args: vec!["--profile".into(), "fast".into()],
        },
    };
    let settings = TerminalSettings {
        terminal_var: Some("xterm"),
        terminal_override: None,
        on_path: &|program| program == "xterm",
    };

    let plan = open_plan(&descriptor, None, Os::Linux, &settings).unwrap();

    assert_eq!(plan.url, None);
    assert_eq!(
        plan.command,
        LaunchCommand {
            program: "xterm".into(),
            args: vec![
                "-e".into(),
                "stt-tui".into(),
                "--profile".into(),
                "fast".into()
            ],
        }
    );
}

#[test]
fn a_gui_frontend_runs_its_own_command() {
    let descriptor = Descriptor {
        version: 1,
        id: "window".into(),
        name: "Window".into(),
        launch: Launch::Gui {
            command: "stt-window".into(),
            args: vec!["--attach".into()],
        },
    };
    let settings = TerminalSettings {
        terminal_var: None,
        terminal_override: None,
        on_path: &|_| false,
    };

    let plan = open_plan(&descriptor, None, Os::Linux, &settings).unwrap();

    assert_eq!(plan.url, None);
    assert_eq!(plan.command.program, "stt-window");
    assert_eq!(plan.command.args, ["--attach"]);
}

#[test]
fn a_browser_frontend_asks_the_service_for_its_one_time_url() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    support::answer_forever(listener, |_| {
        ("200 OK", r#"{"token":"one-time"}"#.to_string())
    });
    let descriptor = Descriptor {
        version: 1,
        id: "browser".into(),
        name: "Browser".into(),
        launch: Launch::Browser { url: "/".into() },
    };
    let settings = TerminalSettings {
        terminal_var: None,
        terminal_override: None,
        on_path: &|_| false,
    };

    let plan = open_plan(
        &descriptor,
        Some(&Service {
            endpoint: format!("http://127.0.0.1:{port}"),
            secret: "the-install-secret".into(),
        }),
        Os::Linux,
        &settings,
    )
    .unwrap();

    assert_eq!(
        plan.url,
        Some(format!("http://127.0.0.1:{port}/open?t=one-time"))
    );
    assert_eq!(plan.command.program, "xdg-open");
    assert_eq!(
        plan.command.args,
        [format!("http://127.0.0.1:{port}/open?t=one-time")]
    );

    let error = open_plan(&descriptor, None, Os::Linux, &settings).unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::NotFound, "{error}");
}

#[test]
fn a_terminal_frontend_with_no_launcher_is_an_error_naming_its_program() {
    let descriptor = Descriptor {
        version: 1,
        id: "tui".into(),
        name: "Terminal TUI".into(),
        launch: Launch::Terminal {
            command: "stt-tui".into(),
            args: Vec::new(),
        },
    };
    let settings = TerminalSettings {
        terminal_var: None,
        terminal_override: None,
        on_path: &|_| false,
    };

    let error = open_plan(&descriptor, None, Os::Linux, &settings).unwrap_err();

    assert_eq!(error.kind(), std::io::ErrorKind::NotFound, "{error}");
    assert!(error.to_string().contains("stt-tui"), "{error}");
}

/// The type exists so the plan's two halves are named; the compiler checks it here.
fn _plan_is(plan: OpenPlan) -> (Option<String>, LaunchCommand) {
    (plan.url, plan.command)
}
