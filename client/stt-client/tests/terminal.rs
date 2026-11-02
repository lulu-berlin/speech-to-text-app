//! Roadmap item 66, test list item 2 (the terminal half): a `terminal` frontend — the TUI — opens
//! in a terminal through a per-OS launcher, since no OS has a "default terminal" to ask for:
//! Windows starts PowerShell, which the configured console host (Windows Terminal when set) draws;
//! macOS asks Terminal; Linux takes the first of a chain that starts with `$TERMINAL` and then
//! `xdg-terminal-exec`. The real launch on each OS is proven by its CI leg (test list item 10).

use stt_client::{LaunchCommand, Os, TerminalSettings, terminal_command};

fn strings(items: &[&str]) -> Vec<String> {
    items.iter().map(|item| item.to_string()).collect()
}

#[test]
fn the_tui_opens_in_each_os_terminal_launcher() {
    let nothing_on_path = |_: &str| false;

    assert_eq!(
        terminal_command(
            r"C:\Program Files\stt-app\stt-tui.exe",
            &strings(&["--theme", "it's dark"]),
            Os::Windows,
            &TerminalSettings {
                terminal_var: None,
                on_path: &nothing_on_path,
                terminal_override: None,
            },
        ),
        Some(LaunchCommand {
            program: "powershell.exe".into(),
            args: strings(&[
                "-Command",
                r"& 'C:\Program Files\stt-app\stt-tui.exe' '--theme' 'it''s dark'",
            ]),
        })
    );
    assert_eq!(
        terminal_command(
            "/Applications/stt-app/stt-tui",
            &[],
            Os::MacOs,
            &TerminalSettings {
                terminal_var: None,
                on_path: &nothing_on_path,
                terminal_override: None,
            },
        ),
        Some(LaunchCommand {
            program: "open".into(),
            args: strings(&["-a", "Terminal", "/Applications/stt-app/stt-tui"]),
        })
    );
    assert_eq!(
        terminal_command(
            "stt-tui",
            &strings(&["--theme", "dark"]),
            Os::Linux,
            &TerminalSettings {
                terminal_var: Some("foot"),
                on_path: &nothing_on_path,
                terminal_override: None,
            },
        ),
        Some(LaunchCommand {
            program: "foot".into(),
            args: strings(&["-e", "stt-tui", "--theme", "dark"]),
        })
    );
    assert_eq!(
        terminal_command(
            "stt-tui",
            &strings(&["--theme", "dark"]),
            Os::Linux,
            &TerminalSettings {
                terminal_var: None,
                on_path: &|program: &str| program == "xdg-terminal-exec",
                terminal_override: None,
            },
        ),
        Some(LaunchCommand {
            program: "xdg-terminal-exec".into(),
            args: strings(&["stt-tui", "--theme", "dark"]),
        })
    );
}

/// D8: `open -a Terminal <program>` cannot pass arguments, so a TUI with arguments goes through
/// AppleScript instead. Terminal's `do script` opens a new window and runs one shell command line
/// in it, so every word is quoted for the shell, and that line is then escaped once more as an
/// AppleScript string. `activate` brings the window to the front.
#[test]
fn on_macos_the_tui_opens_in_terminal_with_its_arguments() {
    assert_eq!(
        terminal_command(
            "/Applications/stt app/stt-tui",
            &strings(&["--theme", "it's dark"]),
            Os::MacOs,
            &TerminalSettings {
                terminal_var: None,
                on_path: &|_: &str| false,
                terminal_override: None,
            },
        ),
        Some(LaunchCommand {
            program: "osascript".into(),
            args: strings(&[
                "-e",
                r#"tell application "Terminal" to do script "'/Applications/stt app/stt-tui' '--theme' 'it'\\''s dark'""#,
                "-e",
                r#"tell application "Terminal" to activate"#,
            ]),
        })
    );
}

/// D9: Linux has no default-terminal setting, so the client walks a chain and takes the first
/// launcher it finds: `$TERMINAL`, `xdg-terminal-exec`, the Debian alternative
/// `x-terminal-emulator`, then GNOME's, KDE's and the X11 terminal, each with its own way of
/// saying "run this program" (`-e`, or `--` for GNOME Terminal). An empty `$TERMINAL` is unset.
#[test]
fn the_linux_terminal_chain_falls_through_to_the_first_launcher_present() {
    let argv = strings(&["--theme", "dark"]);
    let only = |names: &'static [&'static str]| move |program: &str| names.contains(&program);
    let launch = |program: &str, args: &[&str]| {
        Some(LaunchCommand {
            program: program.into(),
            args: strings(args),
        })
    };

    for (present, expected) in [
        (
            &["x-terminal-emulator"][..],
            launch("x-terminal-emulator", &["-e", "stt-tui", "--theme", "dark"]),
        ),
        (
            &["gnome-terminal"][..],
            launch("gnome-terminal", &["--", "stt-tui", "--theme", "dark"]),
        ),
        (
            &["konsole"][..],
            launch("konsole", &["-e", "stt-tui", "--theme", "dark"]),
        ),
        (
            &["xterm"][..],
            launch("xterm", &["-e", "stt-tui", "--theme", "dark"]),
        ),
        (
            &["xterm", "x-terminal-emulator"][..],
            launch("x-terminal-emulator", &["-e", "stt-tui", "--theme", "dark"]),
        ),
        (
            &["xterm", "xdg-terminal-exec"][..],
            launch("xdg-terminal-exec", &["stt-tui", "--theme", "dark"]),
        ),
        (&[][..], None),
    ] {
        assert_eq!(
            terminal_command(
                "stt-tui",
                &argv,
                Os::Linux,
                &TerminalSettings {
                    terminal_var: None,
                    on_path: &only(present),
                    terminal_override: None,
                },
            ),
            expected,
            "present: {present:?}"
        );
    }
    assert_eq!(
        terminal_command(
            "stt-tui",
            &argv,
            Os::Linux,
            &TerminalSettings {
                terminal_var: Some(""),
                on_path: &only(&["xterm"]),
                terminal_override: None,
            },
        ),
        launch("xterm", &["-e", "stt-tui", "--theme", "dark"]),
        "an empty $TERMINAL counts as unset"
    );
}

/// D10: the `terminalCommand` setting is the user's own terminal, written as a command line the
/// TUI's command is appended to. When it is set it replaces the OS launcher on every OS — even a
/// set `$TERMINAL` — and an empty setting leaves the OS launcher in charge. A path with spaces is
/// written in double quotes.
#[test]
fn a_terminal_command_setting_replaces_the_os_launcher() {
    let argv = strings(&["--theme", "dark"]);
    let nothing_on_path = |_: &str| false;

    assert_eq!(
        terminal_command(
            "stt-tui",
            &argv,
            Os::Linux,
            &TerminalSettings {
                terminal_var: Some("foot"),
                on_path: &nothing_on_path,
                terminal_override: Some("wezterm start --"),
            },
        ),
        Some(LaunchCommand {
            program: "wezterm".into(),
            args: strings(&["start", "--", "stt-tui", "--theme", "dark"]),
        })
    );
    assert_eq!(
        terminal_command(
            r"C:\Program Files\stt-app\stt-tui.exe",
            &[],
            Os::Windows,
            &TerminalSettings {
                terminal_var: None,
                on_path: &nothing_on_path,
                terminal_override: Some(r#""C:\Program Files\WezTerm\wezterm.exe" start --"#),
            },
        ),
        Some(LaunchCommand {
            program: r"C:\Program Files\WezTerm\wezterm.exe".into(),
            args: strings(&["start", "--", r"C:\Program Files\stt-app\stt-tui.exe"]),
        })
    );
    assert_eq!(
        terminal_command(
            "stt-tui",
            &argv,
            Os::Linux,
            &TerminalSettings {
                terminal_var: Some("foot"),
                on_path: &nothing_on_path,
                terminal_override: Some(""),
            },
        ),
        Some(LaunchCommand {
            program: "foot".into(),
            args: strings(&["-e", "stt-tui", "--theme", "dark"]),
        })
    );
}
