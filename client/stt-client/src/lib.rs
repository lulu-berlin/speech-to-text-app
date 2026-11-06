//! The client side of the per-user service (roadmap item 66), shared by the tray, the `stt-app` CLI
//! and the TUI.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};
use std::time::{Duration, Instant, SystemTime};

use serde::Deserialize;
use serde::de::DeserializeOwned;

mod autostart;
mod commands;
mod events;
mod quote;
mod upload;

pub use autostart::{Autostart, autostart_entry, install, remove};
pub use commands::{TaskCommand, reorder_queue, set_queue_paused, task_command};
pub use events::{Event, Events, Queue, Snapshot, Task, TaskProgress, events};
pub use upload::{allowed_extension, upload};

/// The per-user directories the service keeps its bootstrap files in: the durable `config.json` in
/// `config_dir`, the ephemeral `runtime.json` in `runtime_dir`.
#[derive(Clone, Debug)]
pub struct ServiceDirs {
    pub config_dir: PathBuf,
    pub runtime_dir: PathBuf,
}

/// Resolved `dirs`, and a `warning` when the runtime directory is a fallback that a logout does
/// not clear.
#[derive(Debug)]
pub struct Resolution {
    pub dirs: ServiceDirs,
    pub warning: Option<String>,
}

const APP_DIR: &str = "stt-app";

impl ServiceDirs {
    /// The directories of this process's user on this OS, with the fallback warning on stderr.
    pub fn from_env() -> io::Result<Self> {
        let resolution = Self::resolve_env()?;
        if let Some(warning) = &resolution.warning {
            eprintln!("stt-app: {warning}");
        }
        Ok(resolution.dirs)
    }

    /// [`resolve`](Self::resolve) over this process's environment and OS, the warning left to the
    /// caller.
    pub fn resolve_env() -> io::Result<Resolution> {
        Self::resolve(
            |name| std::env::var_os(name).map(|value| value.to_string_lossy().into_owned()),
            Os::from(std::env::consts::OS),
        )
    }

    /// The directories the variables `var` names on `os` — the service's rule, shared with it
    /// through `docs/service-dirs.json`: each OS's per-user locations under `stt-app`, or
    /// `STT_CONFIG_DIR` and `STT_RUNTIME_DIR` as they are. An empty variable counts as unset.
    pub fn resolve(var: impl Fn(&str) -> Option<String>, os: Os) -> io::Result<Resolution> {
        let var = |name: &str| var(name).filter(|value| !value.is_empty());
        let home = || {
            var("HOME").or_else(|| var("USERPROFILE")).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotFound,
                    "HOME (or USERPROFILE on Windows) must be set to locate the service's directories",
                )
            })
        };

        let config_dir = match var("STT_CONFIG_DIR") {
            Some(dir) => PathBuf::from(dir),
            None => {
                let base = match os {
                    Os::Linux => match var("XDG_CONFIG_HOME") {
                        Some(base) => PathBuf::from(base),
                        None => PathBuf::from(home()?).join(".config"),
                    },
                    Os::MacOs => PathBuf::from(home()?).join("Library/Application Support"),
                    Os::Windows => match var("APPDATA") {
                        Some(base) => PathBuf::from(base),
                        None => PathBuf::from(home()?).join("AppData/Roaming"),
                    },
                };
                base.join(APP_DIR)
            }
        };

        if let Some(dir) = var("STT_RUNTIME_DIR") {
            return Ok(Resolution {
                dirs: ServiceDirs {
                    config_dir,
                    runtime_dir: PathBuf::from(dir),
                },
                warning: None,
            });
        }
        // The base, and the variable whose absence made it a fallback.
        let (base, missing) = match os {
            Os::Linux => match var("XDG_RUNTIME_DIR") {
                Some(base) => (PathBuf::from(base), None),
                None => (
                    PathBuf::from(home()?).join(".cache"),
                    Some("XDG_RUNTIME_DIR"),
                ),
            },
            Os::MacOs => match var("TMPDIR") {
                Some(base) => (PathBuf::from(base), None),
                None => (
                    PathBuf::from(home()?).join("Library/Caches"),
                    Some("TMPDIR"),
                ),
            },
            Os::Windows => {
                let local = match var("LOCALAPPDATA") {
                    Some(base) => PathBuf::from(base),
                    None => PathBuf::from(home()?).join("AppData/Local"),
                };
                (local.join("Temp"), None)
            }
        };
        let runtime_dir = base.join(APP_DIR);
        let warning = missing.map(|name| {
            format!(
                "{name} is not set: the service's runtime files are in {}, which a logout does not clear",
                runtime_dir.display()
            )
        });
        Ok(Resolution {
            dirs: ServiceDirs {
                config_dir,
                runtime_dir,
            },
            warning,
        })
    }
}

/// The running service as a client reaches it: where it answers and the credential it requires.
#[derive(Clone, Debug)]
pub struct Service {
    pub endpoint: String,
    pub secret: String,
}

/// `config.json`, as far as a client reads it.
#[derive(Deserialize)]
struct Config {
    secret: String,
}

/// `runtime.json`, as far as a client reads it.
#[derive(Deserialize)]
struct Runtime {
    endpoint: String,
}

/// The runtime advertisement, `runtime.json` in the runtime directory: the endpoint and pid of the
/// running service. A clean stop removes it; a crash leaves it behind, and clients then tell the
/// two apart by asking the endpoint (ADR 0064).
fn runtime_file(dirs: &ServiceDirs) -> PathBuf {
    dirs.runtime_dir.join("runtime.json")
}

/// Finds the running service: the endpoint it advertises in `runtime.json` and the per-install
/// secret from `config.json`. The endpoint counts only when it answers `GET /api/health` with
/// `200` to the secret: a `runtime.json` left by a crash, a port another program took, or a
/// service that hangs is `NotFound`, the same as no `runtime.json` at all.
pub async fn locate(dirs: &ServiceDirs) -> io::Result<Service> {
    let runtime: Runtime = read_json(&runtime_file(dirs))?;
    let config: Config = read_json(&dirs.config_dir.join("config.json"))?;
    let service = Service {
        endpoint: runtime.endpoint,
        secret: config.secret,
    };
    if answers_as_the_service(&service).await {
        Ok(service)
    } else {
        Err(io::Error::new(
            io::ErrorKind::NotFound,
            format!(
                "no service answers at {}: runtime.json is a leftover",
                service.endpoint
            ),
        ))
    }
}

/// The service's runner for the `stt-app` binary at `exe`: the sibling `stt-service` (roadmap item 66,
/// decision (b) puts the two in one directory).
pub fn runner_beside(exe: &Path) -> PathBuf {
    exe.with_file_name(format!("stt-service{}", std::env::consts::EXE_SUFFIX))
}

/// The tray's executable beside the `stt-app` binary: `stt-tray`, in the same directory (the
/// packages install the service, the tray and the CLI together).
pub fn tray_beside(exe: &Path) -> PathBuf {
    exe.with_file_name(format!("stt-tray{}", std::env::consts::EXE_SUFFIX))
}

/// Runs the service through `stt-service` beside the calling binary, detached: the service's own
/// instance lock makes a second launch defer to a running one. `stt-app open` and `stt-tray` both
/// start it this way, so the runner's location and name are decided in one place.
pub fn start_runner_beside(dirs: &ServiceDirs) -> io::Result<()> {
    start_beside(
        dirs,
        &runner_beside(&std::env::current_exe()?),
        "service.log",
        &["service"],
    )
}

/// Runs the tray beside the calling binary, detached: the launch that started the service starts
/// the tray (ADR 0064), and the unit is both up or both down.
pub fn start_tray_beside(dirs: &ServiceDirs) -> io::Result<()> {
    start_beside(
        dirs,
        &tray_beside(&std::env::current_exe()?),
        "tray.log",
        &[],
    )
}

/// Starts [program], a sibling of the calling binary, with `args` and logged to `<config>/<log>`.
///
/// The unit outlives the command that started it, so its output must not go to that command's
/// stdout and stderr: those belong to the caller, and a caller capturing them (the e2e harness runs
/// `stt-app open browser --print-url`) would wait for a pipe the unit holds open forever. The
/// service and the tray are therefore detached with their own log file.
fn start_beside(dirs: &ServiceDirs, program: &Path, log: &str, args: &[&str]) -> io::Result<()> {
    let exe = std::env::current_exe()?;
    if !program.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            format!(
                "no {} beside {}",
                program.file_name().unwrap_or_default().to_string_lossy(),
                exe.display()
            ),
        ));
    }
    let logged = |name: &str| {
        fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dirs.config_dir.join(name))
            .map(Stdio::from)
    };
    Command::new(program)
        .args(args)
        .stdout(logged(log)?)
        .stderr(logged(log)?)
        .spawn()
        .map(|_| ())
}

/// A service to use, and whether this launch started it. ADR 0064: the launch that started the
/// unit starts its tray; a launch that attached to a running service starts nothing.
#[derive(Debug)]
pub struct StartOrAttach {
    pub service: Service,
    pub started: bool,
}

/// `locate`'s result, or - when no service answers - [start] once and its result, polled until the
/// service answers `HEALTH_TIMEOUT`'s worth of attempts: a client starts the service only when
/// none runs (roadmap item 66, item 4), and never a second one.
pub async fn ensure_service(
    dirs: &ServiceDirs,
    start: impl FnOnce() -> io::Result<()>,
) -> io::Result<StartOrAttach> {
    let error = match locate(dirs).await {
        Ok(service) => {
            return Ok(StartOrAttach {
                service,
                started: false,
            });
        }
        Err(error) => error,
    };
    if error.kind() != io::ErrorKind::NotFound {
        return Err(error);
    }
    start()?;
    let deadline = Instant::now() + START_TIMEOUT;
    let mut last = error;
    while Instant::now() < deadline {
        match locate(dirs).await {
            Ok(service) => {
                return Ok(StartOrAttach {
                    service,
                    started: true,
                });
            }
            Err(error) => last = error,
        }
        tokio::time::sleep(POLL_INTERVAL).await;
    }
    Err(io::Error::new(
        io::ErrorKind::TimedOut,
        format!("the service did not answer within {START_TIMEOUT:?}: {last}"),
    ))
}

/// Brings the unit up (ADR 0064): the service through [`ensure_service`], then the tray through
/// [start_tray] - but only when this launch started the service. A running service already has
/// its tray, so a launch that attached starts nothing. The unit is both up or both down: when the
/// tray cannot start, the service this launch started is stopped again and the error names the
/// tray.
pub async fn ensure_unit(
    dirs: &ServiceDirs,
    start_service: impl FnOnce() -> io::Result<()>,
    start_tray: impl FnOnce() -> io::Result<()>,
) -> io::Result<StartOrAttach> {
    let run = ensure_service(dirs, start_service).await?;
    if run.started
        && let Err(error) = start_tray()
    {
        let message = match quit(dirs).await {
            Ok(()) => format!(
                "the tray could not start: {error}; the service this launch started was stopped again"
            ),
            Err(quit_error) => format!(
                "the tray could not start: {error}; stopping the service this launch started failed too: {quit_error}"
            ),
        };
        return Err(io::Error::new(error.kind(), message));
    }
    Ok(run)
}

/// Tells the running [service] which tray it is (ADR 0064): the tray's [pid] and the [command] that
/// starts it again, so the service can restart the tray when it dies. ApiError only; a service that
/// is not app mode has no such route and answers 404.
pub async fn register_tray(service: &Service, pid: u32, command: &[String]) -> io::Result<()> {
    let body = serde_json::json!({ "pid": pid, "command": command }).to_string();
    let response = reqwest::Client::new()
        .post(format!("{}/api/tray", service.endpoint))
        .bearer_auth(&service.secret)
        .header("Content-Type", "application/json")
        .body(body)
        .send()
        .await
        .map_err(io::Error::other)?;
    let status = response.status();
    if status.is_success() {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "the service answered {status} to the tray's registration"
        )))
    }
}

/// The tray without an icon (the virtual tray ADR 0064 allows where no tray host exists, and the
/// tray's whole body when `stt-tray` is built without the `tray-menu` feature): it watches the
/// service, restarts it, registers itself so the service can restart it in turn, and ends the
/// process when the unit stops or cannot be kept up.
pub async fn supervise_until_stopped(dirs: &ServiceDirs) -> ExitCode {
    let mut supervisor = Supervisor::new();
    // Whether the service this tray belongs to knows it: every service process needs its own
    // registration, so a restart of the service registers the tray again (ADR 0064).
    let mut registered = false;
    loop {
        match watch_and_register(&mut supervisor, dirs, &mut registered).await {
            Watch::Stopped => return ExitCode::SUCCESS,
            Watch::Stop(error) => {
                eprintln!("stt-tray: {error}; the tray stops too");
                return ExitCode::FAILURE;
            }
            Watch::Restarted => {
                registered = register_self(dirs).await;
                tokio::time::sleep(LOOK_INTERVAL).await;
            }
            Watch::Alive => {
                if !registered {
                    registered = register_self(dirs).await;
                }
                tokio::time::sleep(LOOK_INTERVAL).await;
            }
        }
    }
}

/// One look at the service, plus the tray's registration (ADR 0064): a tray tells each service
/// process who it is, so the service can start it again when it dies. Both the virtual tray and the
/// one with an icon take this step, so the two cannot drift apart.
pub async fn watch_and_register(
    supervisor: &mut Supervisor,
    dirs: &ServiceDirs,
    registered: &mut bool,
) -> Watch {
    let watch = supervisor
        .watch(dirs, Instant::now(), || start_runner_beside(dirs))
        .await;
    match watch {
        Watch::Restarted => *registered = register_self(dirs).await,
        Watch::Alive if !*registered => *registered = register_self(dirs).await,
        Watch::Alive | Watch::Stopped | Watch::Stop(_) => {}
    }
    watch
}

/// How often the tray looks at the service (ADR 0064).
pub const LOOK_INTERVAL: Duration = Duration::from_secs(1);

/// Tells the service who this tray is, so the service can start it again when it dies. False while
/// no service answers yet or the registration failed; the next look tries again.
async fn register_self(dirs: &ServiceDirs) -> bool {
    let registered = async {
        let service = locate(dirs).await?;
        let command = vec![std::env::current_exe()?.to_string_lossy().into_owned()];
        register_tray(&service, std::process::id(), &command).await
    }
    .await;
    match registered {
        Ok(()) => true,
        Err(error) => {
            eprintln!("stt-tray: not registered with the service: {error}");
            false
        }
    }
}

/// What one look at the service tells the tray (ADR 0064).
#[derive(Debug)]
pub enum Watch {
    /// The service answers; nothing was done.
    Alive,
    /// The service was dead and [`watch_service`] started it again.
    Restarted,
    /// The service stopped on purpose: `stt-app quit` has removed `runtime.json`, so the tray
    /// ends too, quietly, instead of starting the service it was asked to leave down.
    Stopped,
    /// The service was dead and could not be started again: the tray stops too, so the unit is
    /// never left half up.
    Stop(io::Error),
}

/// The tray's half of the mutual supervision: a live service is left alone, a dead one is started
/// again through [restart] and waited for, and a restart that fails - or a service that never
/// answers after it - means the tray stops too. A missing `runtime.json` is a clean stop, which the
/// tray honours rather than undoing.
pub async fn watch_service(dirs: &ServiceDirs, restart: impl FnOnce() -> io::Result<()>) -> Watch {
    if !runtime_file(dirs).exists() {
        return Watch::Stopped;
    }
    match ensure_service(dirs, restart).await {
        Ok(StartOrAttach { started: false, .. }) => Watch::Alive,
        Ok(StartOrAttach { started: true, .. }) => Watch::Restarted,
        Err(error) => Watch::Stop(error),
    }
}

/// How many restarts the survivor allows within [`RESTART_WINDOW`]; the next death in that window
/// counts as a failed restart, so a crash loop stops the unit (ADR 0064).
const RESTART_LIMIT: usize = 3;
const RESTART_WINDOW: Duration = Duration::from_secs(60);

/// The tray's supervision across looks: [`watch_service`] plus the restart budget, so a service
/// that keeps dying right after each restart stops the tray instead of being restarted forever.
#[derive(Debug, Default)]
pub struct Supervisor {
    restarts: Vec<Instant>,
}

impl Supervisor {
    pub fn new() -> Self {
        Self::default()
    }

    /// One look at the service at `now`, like [`watch_service`]; a restart beyond
    /// [`RESTART_LIMIT`] within [`RESTART_WINDOW`] is refused and stops the tray.
    pub async fn watch(
        &mut self,
        dirs: &ServiceDirs,
        now: Instant,
        restart: impl FnOnce() -> io::Result<()>,
    ) -> Watch {
        self.restarts
            .retain(|&at| now.duration_since(at) < RESTART_WINDOW);
        let restarts = &mut self.restarts;
        watch_service(dirs, || {
            if restarts.len() >= RESTART_LIMIT {
                return Err(io::Error::other(format!(
                    "the service died again after it was restarted {RESTART_LIMIT} times within {} seconds",
                    RESTART_WINDOW.as_secs()
                )));
            }
            restarts.push(now);
            restart()
        })
        .await
    }
}

/// How long a client waits for a service it just started to answer.
const START_TIMEOUT: Duration = Duration::from_secs(120);
const POLL_INTERVAL: Duration = Duration::from_millis(100);

/// How long a client waits for the service's health answer before it counts the service as hung.
const HEALTH_TIMEOUT: Duration = Duration::from_secs(5);

/// Whether `service`'s endpoint answers `GET /api/health` with `200` to its secret in time.
async fn answers_as_the_service(service: &Service) -> bool {
    let Ok(client) = reqwest::Client::builder().timeout(HEALTH_TIMEOUT).build() else {
        return false;
    };
    client
        .get(format!("{}/api/health", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .is_ok_and(|response| response.status() == reqwest::StatusCode::OK)
}

/// Asks the running service to stop (roadmap item 66, item 4): `POST /api/quit` with the per-install
/// secret, which the service answers `202 Accepted` before it stops. `NotFound` means no service runs.
pub async fn quit(dirs: &ServiceDirs) -> io::Result<()> {
    let service = locate(dirs).await.map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("no running service: {error}"),
            )
        } else {
            error
        }
    })?;
    let response = reqwest::Client::new()
        .post(format!("{}/api/quit", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    let status = response.status();
    if status == reqwest::StatusCode::ACCEPTED {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "the service answered {status} to the quit request"
        )))
    }
}

/// `POST /api/launch-token`'s answer, as far as a client reads it.
#[derive(Deserialize)]
struct LaunchToken {
    token: String,
}

/// The one-time browser URL of the running `service`: a token from `POST /api/launch-token`,
/// asked for with the per-install secret as `Authorization: Bearer`, opened as
/// `<endpoint>/open?t=<token>`. The secret itself never appears in the URL.
pub async fn launch_url(service: &Service) -> io::Result<String> {
    let body = reqwest::Client::new()
        .post(format!("{}/api/launch-token", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(io::Error::other)?
        .text()
        .await
        .map_err(io::Error::other)?;
    let launch: LaunchToken = parse_json(&body)?;
    Ok(format!("{}/open?t={}", service.endpoint, launch.token))
}

/// The operating systems the client launches frontends on, each with its own openers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Os {
    Linux,
    MacOs,
    Windows,
}

impl From<&str> for Os {
    /// `std::env::consts::OS`'s name for the running system; anything else is treated as Linux,
    /// the one that gets the terminal chain.
    fn from(os: &str) -> Self {
        match os {
            "macos" => Os::MacOs,
            "windows" => Os::Windows,
            _ => Os::Linux,
        }
    }
}

/// A program and its arguments, started directly — never through a shell, so no argument is
/// split or reinterpreted on the way.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchCommand {
    pub program: String,
    pub args: Vec<String>,
}

/// What opening a frontend runs: its one-time `url` when it is the browser (and the plan was made
/// with a running service), and the `command` that opens it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenPlan {
    pub url: Option<String>,
    pub command: LaunchCommand,
}

/// The plan for opening [descriptor] on [os] (roadmap item 66): a browser frontend asks `service`
/// for its one-time URL and opens the default browser there; a terminal frontend goes through the
/// OS terminal chain ([terminal_command]); a GUI one runs its own command. `service` is `None` when
/// none runs, which only the browser needs.
pub async fn open_plan(
    descriptor: &Descriptor,
    service: Option<&Service>,
    os: Os,
    settings: &TerminalSettings<'_>,
) -> io::Result<OpenPlan> {
    match &descriptor.launch {
        Launch::Browser { .. } => {
            let url = launch_url(service.ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotFound,
                    "no running service answers, and the browser frontend needs one",
                )
            })?)
            .await?;
            Ok(OpenPlan {
                command: browser_command(&url, os),
                url: Some(url),
            })
        }
        Launch::Terminal { command, args } => Ok(OpenPlan {
            url: None,
            command: terminal_command(command, args, os, settings).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::NotFound,
                    format!("no terminal to run {command} in"),
                )
            })?,
        }),
        Launch::Gui { command, args } => Ok(OpenPlan {
            url: None,
            command: LaunchCommand {
                program: command.clone(),
                args: args.clone(),
            },
        }),
    }
}

/// The command that opens `url` in `os`'s default browser: `xdg-open` on Linux, `open` on macOS,
/// and on Windows `explorer`, which hands a URL to the shell's default-browser association.
pub fn browser_command(url: &str, os: Os) -> LaunchCommand {
    let program = match os {
        Os::Linux => "xdg-open",
        Os::MacOs => "open",
        Os::Windows => "explorer",
    };
    LaunchCommand {
        program: program.into(),
        args: vec![url.into()],
    }
}

/// What opening a terminal depends on besides the OS: the user's `$TERMINAL`, the `terminalCommand`
/// setting, and a way to look a program up on `PATH` (a parameter, so tests can answer it).
pub struct TerminalSettings<'a> {
    pub terminal_var: Option<&'a str>,
    pub terminal_override: Option<&'a str>,
    pub on_path: &'a dyn Fn(&str) -> bool,
}

/// The command that opens `command` with `args` in a terminal on `os`; no OS has a "default
/// terminal" to ask for. A non-empty `terminal_override` — the user's `terminalCommand` setting, a
/// command line split as a POSIX shell splits it — wins on every OS, with the TUI's command
/// appended. Otherwise Windows starts PowerShell, which the configured console host (Windows
/// Terminal when set) draws. macOS opens the program with Terminal (`open -a Terminal`), and, since
/// that cannot pass arguments, has Terminal run the whole command line through AppleScript when
/// there are any. Linux takes the first of its chain: a non-empty `$TERMINAL` (`terminal_var`) with
/// `-e`, then the first of [`LINUX_TERMINALS`] that `on_path` finds. `None` when no launcher fits:
/// Linux past the end of the chain, or a setting that does not split (an unclosed quote).
pub fn terminal_command(
    command: &str,
    args: &[String],
    os: Os,
    settings: &TerminalSettings<'_>,
) -> Option<LaunchCommand> {
    let argv = || {
        std::iter::once(command.to_string())
            .chain(args.iter().cloned())
            .collect::<Vec<String>>()
    };
    if let Some(setting) = settings
        .terminal_override
        .filter(|setting| !setting.trim().is_empty())
    {
        let mut words = shlex::split(setting)?.into_iter();
        let program = words.next()?;
        return Some(LaunchCommand {
            program,
            args: words.chain(argv()).collect(),
        });
    }
    match os {
        Os::Windows => {
            let line = argv()
                .iter()
                .map(|word| quote::powershell(word))
                .collect::<Vec<String>>()
                .join(" ");
            Some(LaunchCommand {
                program: "powershell.exe".into(),
                args: vec!["-Command".into(), format!("& {line}")],
            })
        }
        Os::MacOs if args.is_empty() => Some(LaunchCommand {
            program: "open".into(),
            args: vec!["-a".into(), "Terminal".into(), command.into()],
        }),
        Os::MacOs => {
            let line = argv()
                .iter()
                .map(|word| quote::posix(word))
                .collect::<Vec<String>>()
                .join(" ");
            Some(LaunchCommand {
                program: "osascript".into(),
                args: vec![
                    "-e".into(),
                    format!(
                        "tell application \"Terminal\" to do script {}",
                        quote::applescript_string(&line)
                    ),
                    "-e".into(),
                    "tell application \"Terminal\" to activate".into(),
                ],
            })
        }
        Os::Linux => {
            if let Some(terminal) = settings
                .terminal_var
                .filter(|terminal| !terminal.is_empty())
            {
                return Some(LaunchCommand {
                    program: terminal.into(),
                    args: std::iter::once("-e".to_string()).chain(argv()).collect(),
                });
            }
            LINUX_TERMINALS
                .iter()
                .find(|(program, _)| (settings.on_path)(program))
                .map(|(program, run)| LaunchCommand {
                    program: (*program).into(),
                    args: run
                        .iter()
                        .map(|word| word.to_string())
                        .chain(argv())
                        .collect(),
                })
        }
    }
}

/// The Linux terminal chain after `$TERMINAL`, in order, each with the words that make it run a
/// program: the freedesktop launcher, the Debian alternative, GNOME's, KDE's, then X11's.
const LINUX_TERMINALS: [(&str, &[&str]); 5] = [
    ("xdg-terminal-exec", &[]),
    ("x-terminal-emulator", &["-e"]),
    ("gnome-terminal", &["--"]),
    ("konsole", &["-e"]),
    ("xterm", &["-e"]),
];

/// `GET /api/settings`, as far as a client reads it.
#[derive(Deserialize)]
struct Settings {
    #[serde(rename = "defaultFrontend")]
    default_frontend: Option<String>,
    #[serde(rename = "terminalCommand")]
    terminal_command: Option<String>,
}

/// The id of the default frontend the user picked in the settings (roadmap item 66), or `None` while none is set.
pub async fn default_frontend(service: &Service) -> io::Result<Option<String>> {
    Ok(settings_of(service).await?.default_frontend)
}

/// The terminal command the user set for the TUI (roadmap item 66, D10), or `None` for the OS launcher.
pub async fn terminal_command_setting(service: &Service) -> io::Result<Option<String>> {
    Ok(settings_of(service).await?.terminal_command)
}

/// `GET /api/settings` with the per-install secret.
async fn settings_of(service: &Service) -> io::Result<Settings> {
    let body = reqwest::Client::new()
        .get(format!("{}/api/settings", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(io::Error::other)?
        .text()
        .await
        .map_err(io::Error::other)?;
    parse_json(&body)
}

/// One installed frontend, as its installer registered it: one JSON file per frontend in the
/// per-user registry directory (`…/stt-app/frontends`).
#[derive(Debug, PartialEq, Deserialize)]
pub struct Descriptor {
    pub version: u32,
    pub id: String,
    pub name: String,
    #[serde(flatten)]
    pub launch: Launch,
}

/// How a frontend is opened, by its descriptor's `kind`: in the OS browser at a URL on the
/// service, as a GUI program, or as a command in a terminal.
#[derive(Debug, PartialEq, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Launch {
    Browser {
        url: String,
    },
    Gui {
        command: String,
        #[serde(default)]
        args: Vec<String>,
    },
    Terminal {
        command: String,
        #[serde(default)]
        args: Vec<String>,
    },
}

/// A registry file the client could not use — malformed, or of a `kind` it does not know — and
/// why; the tray and the CLI log each one as a warning.
#[derive(Debug)]
pub struct Skipped {
    pub file: String,
    pub reason: String,
}

/// The installed frontends, in file-name order, and the registry files that were skipped.
#[derive(Debug, Default)]
pub struct Registry {
    pub frontends: Vec<Descriptor>,
    pub skipped: Vec<Skipped>,
}

impl Launch {
    /// The descriptor's `kind` as a word: `browser`, `gui` or `terminal`. The tray, `stt-app list` and the
    /// service's `GET /api/frontends` name a frontend this way.
    pub fn kind(&self) -> &'static str {
        match self {
            Launch::Browser { .. } => "browser",
            Launch::Gui { .. } => "gui",
            Launch::Terminal { .. } => "terminal",
        }
    }
}

/// One row of the tray's frontend menu (roadmap item 66): an installed frontend, its kind, and whether it is the
/// default the settings name.
pub struct TrayEntry<'a> {
    pub id: &'a str,
    pub name: &'a str,
    pub kind: &'static str,
    pub is_default: bool,
}

/// The tray's menu entries: every installed frontend in the registry's order, with the default marked. A default
/// that is not installed marks nothing, so an uninstalled frontend is never shown as the default.
pub fn tray_entries<'a>(registry: &'a Registry, default: Option<&str>) -> Vec<TrayEntry<'a>> {
    registry
        .frontends
        .iter()
        .map(|frontend| TrayEntry {
            id: &frontend.id,
            name: &frontend.name,
            kind: frontend.launch.kind(),
            is_default: default == Some(frontend.id.as_str()),
        })
        .collect()
}

impl Registry {
    /// The frontend registered under `id`, if any; the tray and `stt-app` both look one up this way.
    pub fn find(&self, id: &str) -> Option<&Descriptor> {
        self.frontends.iter().find(|frontend| frontend.id == id)
    }

    /// The installed frontends' ids, for a message that tells the user what they could open.
    pub fn ids(&self) -> Vec<&str> {
        self.frontends
            .iter()
            .map(|frontend| frontend.id.as_str())
            .collect()
    }
}

/// Reads every `*.json` descriptor in `dir`. A file that does not parse as a descriptor is
/// skipped, not fatal, so one bad file never hides the others.
/// Installs the descriptor in `source` as a registered frontend under `config_dir` (roadmap item 66):
/// parsed first, so a broken descriptor never enters the registry, and written whole or not at all.
/// Returns the frontend's id.
pub fn register_descriptor(config_dir: &Path, source: &Path) -> io::Result<String> {
    let descriptor: Descriptor = read_json(source)?;
    let safe = !descriptor.id.is_empty()
        && descriptor.id.chars().all(|character| {
            character.is_ascii_alphanumeric() || character == '-' || character == '_'
        });
    if !safe {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("{:?} is not a usable frontend id", descriptor.id),
        ));
    }
    let frontends = config_dir.join("frontends");
    fs::create_dir_all(&frontends)?;
    let target = frontends.join(format!("{}.json", descriptor.id));
    let temp = frontends.join(format!(".{}.json.tmp", descriptor.id));
    fs::write(&temp, fs::read_to_string(source)?)?;
    fs::rename(&temp, &target)?;
    Ok(descriptor.id)
}

/// What the frontend registry looks like now: every descriptor's file name and modification time,
/// in name order. Any install or uninstall changes it, which is how the tray notices a frontend
/// added while it runs.
pub fn registry_fingerprint(dirs: &ServiceDirs) -> Vec<(String, Option<SystemTime>)> {
    let Ok(entries) = fs::read_dir(dirs.config_dir.join("frontends")) else {
        return Vec::new();
    };
    let mut seen: Vec<(String, Option<SystemTime>)> = entries
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.path().extension().is_some_and(|kind| kind == "json"))
        .map(|entry| {
            let modified = entry.metadata().and_then(|meta| meta.modified()).ok();
            (entry.file_name().to_string_lossy().into_owned(), modified)
        })
        .collect();
    seen.sort();
    seen
}

/// The installed frontends as the tray, `stt-app` and the service all read them: the `frontends`
/// directory inside the config dir (the service serves exactly this directory, and
/// `stt-app register` writes into it). No directory means nothing is installed; unreadable files are
/// skipped by [`read_registry`] with their warnings.
pub fn registry_for(dirs: &ServiceDirs) -> Registry {
    let frontends = dirs.config_dir.join("frontends");
    if !frontends.is_dir() {
        return Registry::default();
    }
    read_registry(&frontends).unwrap_or_else(|error| {
        eprintln!("stt-tray: cannot read {}: {error}", frontends.display());
        Registry::default()
    })
}

pub fn read_registry(dir: &Path) -> io::Result<Registry> {
    let mut files = fs::read_dir(dir)?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<io::Result<Vec<PathBuf>>>()?;
    files.retain(|file| {
        file.extension()
            .is_some_and(|extension| extension == "json")
    });
    files.sort();
    let mut registry = Registry {
        frontends: Vec::new(),
        skipped: Vec::new(),
    };
    for file in files {
        match read_json::<Descriptor>(&file) {
            Ok(descriptor) => registry.frontends.push(descriptor),
            Err(error) => registry.skipped.push(Skipped {
                file: file
                    .file_name()
                    .map(|name| name.to_string_lossy().into_owned())
                    .unwrap_or_default(),
                reason: error.to_string(),
            }),
        }
    }
    Ok(registry)
}

fn read_json<T: DeserializeOwned>(file: &Path) -> io::Result<T> {
    parse_json(&fs::read_to_string(file)?)
}

/// `text` as JSON of type `T`; a mismatch is `InvalidData`.
fn parse_json<T: DeserializeOwned>(text: &str) -> io::Result<T> {
    serde_json::from_str(text).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

/// The service's error body (`error.json`), as far as a client reads it.
#[derive(Deserialize)]
pub(crate) struct ApiError {
    message: String,
}

/// A response's body as type `T`; a response that is not a success is the service's own `message`
/// as the error, which is what a client shows.
pub(crate) async fn response_json<T: DeserializeOwned>(
    response: reqwest::Response,
) -> io::Result<T> {
    let status = response.status();
    let body = response.text().await.map_err(io::Error::other)?;
    if status.is_success() {
        return parse_json(&body);
    }
    let message = parse_json::<ApiError>(&body)
        .map(|error| error.message)
        .unwrap_or_else(|_| format!("the service answered {status}"));
    Err(io::Error::other(message))
}
