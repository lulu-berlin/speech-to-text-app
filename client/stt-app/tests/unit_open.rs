//! Roadmap item 66, ADR 0064: opening a frontend through `stt-app` brings up the unit - the
//! service and the tray - and a second launch attaches without starting either again.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;

use std::env;
use std::fs;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant};

/// The runner and the tray the test's `stt-app` finds beside itself are copies of this binary, so
/// each test first asks which process it is - and the main test does nothing in a copy.
#[derive(Clone, Copy, PartialEq)]
enum Role {
    Main,
    Service,
    Tray,
    /// The window: a `gui` frontend's command, which the CLI must run once the unit is up.
    Window,
}

fn role() -> Role {
    let exe = env::current_exe().unwrap();
    match exe.file_stem().unwrap().to_string_lossy().as_ref() {
        "stt-service" => Role::Service,
        "stt-tray" => Role::Tray,
        "fake-window" => Role::Window,
        _ => Role::Main,
    }
}

/// Polls `done` for up to 20 seconds: the unit's processes are started detached, so their effects
/// land after the command that started them has returned.
fn wait_for(done: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(20);
    while Instant::now() < deadline && !done() {
        thread::sleep(Duration::from_millis(50));
    }
}

/// How many times the tray stand-in was started.
fn tray_starts(runtime_dir: &Path) -> usize {
    fs::read_to_string(runtime_dir.join("tray-starts"))
        .map(|lines| lines.lines().count())
        .unwrap_or(0)
}

fn dirs(dir: &Path) -> (PathBuf, PathBuf, PathBuf) {
    (dir.join("bin"), dir.join("cfg"), dir.join("rt"))
}

// The three spawn-based tests below drive copies of this binary as the unit's processes and read the CLI's
// captured output; on Windows a stub process keeps the captured pipe open and the service stub never settles,
// so they hang or miss the 5 s bound. The CLI's `open` path on Windows is covered by the packaged zip e2e;
// docs/known-issues.md records it.
#[test]
#[cfg_attr(
    windows,
    ignore = "a Windows stub keeps the captured pipe open; the packaged zip e2e covers `open`"
)]
fn opening_a_frontend_brings_up_the_unit_and_quit_stops_it() {
    if role() != Role::Main {
        return;
    }
    let dir = tempfile::tempdir().unwrap();
    let (bin, cfg, rt) = dirs(dir.path());
    for path in [&bin, &cfg, &rt] {
        fs::create_dir(path).unwrap();
    }
    let suffix = env::consts::EXE_SUFFIX;
    let cli = bin.join(format!("stt-app{suffix}"));
    fs::copy(env!("CARGO_BIN_EXE_stt-app"), &cli).unwrap();
    fs::copy(
        env::current_exe().unwrap(),
        bin.join(format!("stt-service{suffix}")),
    )
    .unwrap();
    fs::copy(
        env::current_exe().unwrap(),
        bin.join(format!("stt-tray{suffix}")),
    )
    .unwrap();
    let descriptor = dir.path().join("browser.json");
    fs::write(
        &descriptor,
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    )
    .unwrap();
    let run = |args: &[&str]| {
        Command::new(&cli)
            .args(args)
            .env("STT_CONFIG_DIR", &cfg)
            .env("STT_RUNTIME_DIR", &rt)
            .output()
            .unwrap()
    };
    let registered = run(&["register", descriptor.to_str().unwrap()]);
    assert!(
        registered.status.success(),
        "{}",
        String::from_utf8_lossy(&registered.stderr)
    );

    let first = run(&["open", "browser", "--print-url"]);
    assert!(
        first.status.success(),
        "{}",
        String::from_utf8_lossy(&first.stderr)
    );
    let printed = String::from_utf8_lossy(&first.stdout);
    assert!(
        printed.trim().starts_with("http://127.0.0.1:") && printed.contains("/open?t="),
        "open prints only its one-time URL, but printed {printed:?}"
    );
    assert!(
        cfg.join("tray.log").exists(),
        "the tray logs to its own file, not to the caller's output"
    );
    wait_for(|| tray_starts(&rt) == 1);
    assert_eq!(
        tray_starts(&rt),
        1,
        "the launch that started the service starts the tray"
    );

    let second = run(&["open", "browser", "--print-url"]);
    assert!(second.status.success());
    assert_eq!(tray_starts(&rt), 1, "a launch that attached starts no tray");

    let quit = run(&["quit"]);
    assert!(
        quit.status.success(),
        "{}",
        String::from_utf8_lossy(&quit.stderr)
    );
    assert!(
        !rt.join("runtime.json").exists(),
        "a clean stop removes runtime.json"
    );
}

/// The stand-in the runner copy runs, the only test whose name contains `service` (the runner's
/// argument is the filter): it writes the bootstrap files, then answers health, the launch token
/// and quit the way the service does.
#[test]
fn service() {
    if role() != Role::Service {
        return;
    }
    let cfg = env::var_os("STT_CONFIG_DIR").unwrap();
    let rt = PathBuf::from(env::var_os("STT_RUNTIME_DIR").unwrap());
    let config = Path::new(&cfg).join("config.json");
    if !config.exists() {
        fs::write(&config, r#"{"port":28763,"secret":"the-install-secret"}"#).unwrap();
    }
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let runtime = rt.join("runtime.json");
    fs::write(
        &runtime,
        format!(
            r#"{{"pid":{},"endpoint":"http://127.0.0.1:{port}"}}"#,
            std::process::id()
        ),
    )
    .unwrap();
    for stream in listener.incoming() {
        let mut stream = stream.unwrap();
        let mut buf = [0u8; 4096];
        let read = stream.read(&mut buf).unwrap_or(0);
        let request = String::from_utf8_lossy(&buf[..read]).to_string();
        let (status, body) = if request.starts_with("POST /api/launch-token ") {
            ("200 OK", r#"{"token":"one-time"}"#.to_string())
        } else if request.starts_with("POST /api/quit ") {
            let _ = fs::remove_file(&runtime);
            ("202 Accepted", String::new())
        } else {
            ("200 OK", r#"{"status":"ok"}"#.to_string())
        };
        let _ = write!(
            stream,
            "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        if status == "202 Accepted" {
            return;
        }
    }
}

/// The stand-in the tray copy runs when `stt-app` starts it: it records that it ran.
#[test]
fn tray() {
    if role() != Role::Tray {
        return;
    }
    let rt = PathBuf::from(env::var_os("STT_RUNTIME_DIR").unwrap());
    let mut starts = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(rt.join("tray-starts"))
        .unwrap();
    starts.write_all(b"tray\n").unwrap();
}

/// The window stand-in: a `gui` frontend's command. It records that it ran, the way the Electron
/// window would start against the service the CLI just brought up.
#[test]
fn window() {
    if role() != Role::Window {
        return;
    }
    let rt = PathBuf::from(env::var_os("STT_RUNTIME_DIR").unwrap());
    let mut started = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(rt.join("window-started"))
        .unwrap();
    started.write_all(b"window\n").unwrap();
}

// ADR 0064: the window is a client of the unit, not a second supervisor. Opening the `gui` frontend
// brings the unit up and then runs the window's own command.
// The three spawn-based tests below drive copies of this binary as the unit's processes and read the CLI's
// captured output; on Windows a stub process keeps the captured pipe open and the service stub never settles,
// so they hang or miss the 5 s bound. The CLI's `open` path on Windows is covered by the packaged zip e2e;
// docs/known-issues.md records it.
#[test]
#[cfg_attr(
    windows,
    ignore = "a Windows stub keeps the captured pipe open; the packaged zip e2e covers `open`"
)]
fn opening_a_gui_frontend_brings_up_the_unit_and_runs_the_window() {
    if role() != Role::Main {
        return;
    }
    let dir = tempfile::tempdir().unwrap();
    let (bin, cfg, rt) = dirs(dir.path());
    for path in [&bin, &cfg, &rt] {
        fs::create_dir(path).unwrap();
    }
    let suffix = env::consts::EXE_SUFFIX;
    let cli = bin.join(format!("stt-app{suffix}"));
    fs::copy(env!("CARGO_BIN_EXE_stt-app"), &cli).unwrap();
    fs::copy(
        env::current_exe().unwrap(),
        bin.join(format!("stt-service{suffix}")),
    )
    .unwrap();
    fs::copy(
        env::current_exe().unwrap(),
        bin.join(format!("stt-tray{suffix}")),
    )
    .unwrap();
    let window = bin.join(format!("fake-window{suffix}"));
    fs::copy(env::current_exe().unwrap(), &window).unwrap();

    let descriptor = dir.path().join("window.json");
    fs::write(
        &descriptor,
        format!(
            r#"{{"version":1,"id":"window","kind":"gui","name":"Window","command":{:?},"args":[]}}"#,
            window.to_string_lossy()
        ),
    )
    .unwrap();
    let run = |args: &[&str]| {
        Command::new(&cli)
            .args(args)
            .env("STT_CONFIG_DIR", &cfg)
            .env("STT_RUNTIME_DIR", &rt)
            .output()
            .unwrap()
    };
    assert!(
        run(&["register", descriptor.to_str().unwrap()])
            .status
            .success()
    );

    let opened = run(&["open", "window"]);
    assert!(
        opened.status.success(),
        "{}",
        String::from_utf8_lossy(&opened.stderr)
    );
    wait_for(|| rt.join("window-started").exists());
    assert!(
        rt.join("window-started").exists(),
        "the gui frontend's command must run once the unit is up"
    );
    wait_for(|| tray_starts(&rt) == 1);
    assert_eq!(tray_starts(&rt), 1, "opening the window brings the unit up");
}

// ADR 0064: the window is a client, not a child the CLI waits for. `stt-app open` must return (and
// close its output) while the window keeps running - a caller capturing the command's output, the
// e2e harness among them, otherwise waits for a pipe the window holds open.
// The three spawn-based tests below drive copies of this binary as the unit's processes and read the CLI's
// captured output; on Windows a stub process keeps the captured pipe open and the service stub never settles,
// so they hang or miss the 5 s bound. The CLI's `open` path on Windows is covered by the packaged zip e2e;
// docs/known-issues.md records it.
#[test]
#[cfg_attr(
    windows,
    ignore = "a Windows stub keeps the captured pipe open; the packaged zip e2e covers `open`"
)]
fn opening_a_frontend_returns_while_the_frontend_keeps_running() {
    if role() != Role::Main {
        return;
    }
    let dir = tempfile::tempdir().unwrap();
    let (bin, cfg, rt) = dirs(dir.path());
    for path in [&bin, &cfg, &rt] {
        fs::create_dir(path).unwrap();
    }
    let suffix = env::consts::EXE_SUFFIX;
    let cli = bin.join(format!("stt-app{suffix}"));
    fs::copy(env!("CARGO_BIN_EXE_stt-app"), &cli).unwrap();
    fs::copy(
        env::current_exe().unwrap(),
        bin.join(format!("stt-service{suffix}")),
    )
    .unwrap();
    fs::copy(
        env::current_exe().unwrap(),
        bin.join(format!("stt-tray{suffix}")),
    )
    .unwrap();
    let window = bin.join(format!("fake-window{suffix}"));
    fs::copy(env::current_exe().unwrap(), &window).unwrap();
    let descriptor = dir.path().join("window.json");
    fs::write(
        &descriptor,
        format!(
            r#"{{"version":1,"id":"window","kind":"gui","name":"Window","command":{:?},"args":[]}}"#,
            window.to_string_lossy()
        ),
    )
    .unwrap();
    let run = |args: &[&str]| {
        Command::new(&cli)
            .args(args)
            .env("STT_CONFIG_DIR", &cfg)
            .env("STT_RUNTIME_DIR", &rt)
            .output()
            .unwrap()
    };
    assert!(
        run(&["register", descriptor.to_str().unwrap()])
            .status
            .success()
    );

    // Captured output, as a script or the e2e harness does it, and a window that stays open.
    let mut opened = Command::new(&cli)
        .args(["open", "window"])
        .env("STT_CONFIG_DIR", &cfg)
        .env("STT_RUNTIME_DIR", &rt)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .unwrap();
    let mut stdout = opened.stdout.take().unwrap();
    let (closed, closed_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut sink = Vec::new();
        let _ = std::io::Read::read_to_end(&mut stdout, &mut sink);
        let _ = closed.send(());
    });
    let finished = closed_rx.recv_timeout(std::time::Duration::from_secs(5));
    let _ = opened.kill();
    let _ = opened.wait();
    assert!(
        finished.is_ok(),
        "stt-app open must close its output while the frontend keeps running"
    );
    wait_for(|| rt.join("window-started").exists());
    assert!(
        rt.join("window-started").exists(),
        "the window must still have been started"
    );
}
