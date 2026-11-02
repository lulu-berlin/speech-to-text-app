//! The user-scoped autostart entry (roadmap item 66, item 5): what gets installed so the service runs
//! at login, per OS. Nothing here touches the system; the caller writes [Autostart::File] or sets
//! [Autostart::WindowsRun], and the CI legs prove the OS acts on it.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::Os;

/// The LaunchAgent's label: reverse-DNS, and the plist's file name.
const LABEL: &str = "dev.sttapp.service";

/// The Windows key whose values run at login for the current user.
const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

/// Installed autostart: a file to write, or the `HKCU\…\Run` value to set.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Autostart {
    File {
        path: PathBuf,
        content: String,
    },
    WindowsRun {
        key: &'static str,
        name: &'static str,
        command: String,
    },
}

/// The entry that runs [runner] in service mode at login on [os], for the user whose home is [home].
/// The runner is a sibling of `stt-app` (that layout is the packaging's), and every OS gets `service`
/// as its argument — the backend's app mode.
pub fn autostart_entry(os: Os, home: &Path, runner: &Path) -> Autostart {
    let program = runner.display();
    match os {
        Os::Linux => Autostart::File {
            path: home.join(".config/autostart/stt-app.desktop"),
            content: format!(
                "[Desktop Entry]
Type=Application
Name=Speech to Text
Comment=Transcribes audio on this machine
Exec=\"{program}\" service
Terminal=false
X-GNOME-Autostart-enabled=true
"
            ),
        },
        Os::MacOs => Autostart::File {
            path: home.join("Library/LaunchAgents").join(format!("{LABEL}.plist")),
            content: format!(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
    <key>Label</key>
    <string>{LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>{program}</string>
        <string>service</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
"
            ),
        },
        Os::Windows => Autostart::WindowsRun {
            key: RUN_KEY,
            name: "stt-app",
            command: format!("\"{program}\" service"),
        },
    }
}

/// Installs [entry]: writes the file (its directory created, the file replaced whole) or sets the
/// `HKCU\…\Run` value with `reg`. The OS acts on it at the next login; nothing here restarts anything.
pub fn install(entry: &Autostart) -> io::Result<()> {
    match entry {
        Autostart::File { path, content } => {
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)?;
            }
            let temp = path.with_file_name(format!(
                ".{}.tmp",
                path.file_name().unwrap_or_default().to_string_lossy()
            ));
            fs::write(&temp, content)?;
            fs::rename(&temp, path)
        }
        Autostart::WindowsRun { key, name, command } => reg(&[
            "add",
            &format!("HKCU\\{key}"),
            "/v",
            name,
            "/t",
            "REG_SZ",
            "/d",
            command,
            "/f",
        ]),
    }
}

/// Removes [entry]; a login entry that is not there is not an error, so `off` twice succeeds.
pub fn remove(entry: &Autostart) -> io::Result<()> {
    match entry {
        Autostart::File { path, .. } => match fs::remove_file(path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error),
        },
        Autostart::WindowsRun { key, name, .. } => {
            let status = Command::new("reg")
                .args(["delete", &format!("HKCU\\{key}"), "/v", name, "/f"])
                .status()?;
            // `reg delete` answers 1 when the value is already gone.
            if status.success() || status.code() == Some(1) {
                Ok(())
            } else {
                Err(io::Error::other(format!("reg delete failed: {status}")))
            }
        }
    }
}

fn reg(args: &[&str]) -> io::Result<()> {
    let status = Command::new("reg").args(args).status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!("reg failed: {status}")))
    }
}
