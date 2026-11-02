//! `stt-app`, the client command (roadmap item 66): it talks to the per-user service from the
//! outside — `open` reaches it through a registered frontend, `list` shows what is installed.

use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};

use stt_client::{
    Descriptor, Launch, Os, Registry, Service, ServiceDirs, StartOrAttach, TerminalSettings,
    autostart_entry, default_frontend, ensure_unit, install, locate, open_plan, quit,
    read_registry, register_descriptor, remove, runner_beside, start_runner_beside,
    start_tray_beside, terminal_command_setting,
};

fn main() -> ExitCode {
    match std::env::args().nth(1).as_deref() {
        Some("open") => open(&std::env::args().skip(2).collect::<Vec<String>>()),
        Some("list") => list(),
        Some("quit") => quit_service(),
        Some("register") => register(&std::env::args().skip(2).collect::<Vec<String>>()),
        Some("autostart") => set_autostart(&std::env::args().skip(2).collect::<Vec<String>>()),
        _ => {
            eprintln!(
                "usage: stt-app open [<id>] [--print-url] | stt-app list | stt-app quit | stt-app register <descriptor> | stt-app autostart on|off"
            );
            ExitCode::from(2)
        }
    }
}

/// Opens the frontend named by `id`, or says which ones are installed.
fn open(args: &[String]) -> ExitCode {
    let Some(dirs) = dirs() else {
        return ExitCode::FAILURE;
    };
    let registry = registry(&dirs.config_dir);
    let mut located = Located::new(&dirs);
    let Some(descriptor) = select(&mut located, &registry, args) else {
        return ExitCode::FAILURE;
    };
    let print_url = args.iter().any(|arg| arg == "--print-url");
    let terminal_var = std::env::var("TERMINAL").ok();
    // Every frontend is served by the service (decision (a)): opening one starts it when none runs,
    // through the runner beside `stt-app`, and waits for it before the frontend is opened.
    let service = match located.unit_or_start() {
        Ok(service) => service,
        Err(error) => {
            eprintln!("stt-app: {error}");
            return ExitCode::FAILURE;
        }
    };
    // The terminal command the user set on the Settings page replaces the OS launcher (item 66, D10); a read that
    // fails falls back to it with a warning, so a broken settings read never blocks the frontend.
    let terminal_override = if matches!(descriptor.launch, Launch::Terminal { .. }) {
        match terminal_command_setting(service) {
            Ok(command) => command,
            Err(error) => {
                eprintln!("stt-app: cannot read the terminal command setting: {error}");
                None
            }
        }
    } else {
        None
    };
    let settings = TerminalSettings {
        terminal_var: terminal_var.as_deref(),
        terminal_override: terminal_override.as_deref(),
        on_path: &on_path,
    };
    let plan = match open_plan(descriptor, Some(service), host_os(), &settings) {
        Ok(plan) => plan,
        Err(error) => {
            eprintln!("stt-app: {error}");
            return ExitCode::FAILURE;
        }
    };
    if print_url && let Some(url) = &plan.url {
        println!("{url}");
        return ExitCode::SUCCESS;
    }
    spawn(&plan.command.program, &plan.command.args)
}

/// Turns the login entry on or off: the service runner beside this binary, started at login with the
/// user's own mechanism (XDG autostart, LaunchAgent, HKCU Run).
fn set_autostart(args: &[String]) -> ExitCode {
    let enable = match args.first().map(String::as_str) {
        Some("on") => true,
        Some("off") => false,
        _ => {
            eprintln!("usage: stt-app autostart on|off");
            return ExitCode::from(2);
        }
    };
    let Some(home) = home_dir() else {
        eprintln!("stt-app: HOME (or USERPROFILE) must be set to name the login entry");
        return ExitCode::FAILURE;
    };
    let exe = match std::env::current_exe() {
        Ok(exe) => exe,
        Err(error) => {
            eprintln!("stt-app: cannot find its own path: {error}");
            return ExitCode::FAILURE;
        }
    };
    let entry = autostart_entry(host_os(), &home, &runner_beside(&exe));
    let result = if enable {
        install(&entry)
    } else {
        remove(&entry)
    };
    match result {
        Ok(()) => {
            println!(
                "stt-app: autostart is {}",
                if enable { "on" } else { "off" }
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!(
                "stt-app: cannot turn autostart {enable_word}: {error}",
                enable_word = if enable { "on" } else { "off" }
            );
            ExitCode::FAILURE
        }
    }
}

/// The user's home directory, where every OS keeps the login entry.
fn home_dir() -> Option<PathBuf> {
    ["HOME", "USERPROFILE"]
        .iter()
        .filter_map(std::env::var_os)
        .map(PathBuf::from)
        .find(|path| !path.as_os_str().is_empty())
}

/// Installs a frontend descriptor in the user's registry; it needs no service, only the config directory.
fn register(args: &[String]) -> ExitCode {
    let Some(source) = args.first() else {
        eprintln!("usage: stt-app register <descriptor>");
        return ExitCode::from(2);
    };
    let Some(config_dir) = config_dir() else {
        return ExitCode::FAILURE;
    };
    match register_descriptor(&config_dir, Path::new(source)) {
        Ok(id) => {
            println!("stt-app: registered {id}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("stt-app: cannot register {source}: {error}");
            ExitCode::FAILURE
        }
    }
}

/// Asks the running service to stop; quit is explicit, so a frontend closing leaves it running.
fn quit_service() -> ExitCode {
    let Some(dirs) = dirs() else {
        return ExitCode::FAILURE;
    };
    match quit(&dirs) {
        Ok(()) => {
            println!("stt-app: the service is stopping");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("stt-app: cannot quit the service: {error}");
            ExitCode::FAILURE
        }
    }
}

/// Prints one line per installed frontend (`id`, `name`, `kind`) and warns on stderr about every
/// descriptor it had to skip, so one bad file is visible without hiding the others.
fn list() -> ExitCode {
    let Some(config_dir) = config_dir() else {
        return ExitCode::FAILURE;
    };
    let registry = registry(&config_dir);
    for skipped in &registry.skipped {
        eprintln!("stt-app: skipping {}: {}", skipped.file, skipped.reason);
    }
    for frontend in &registry.frontends {
        println!(
            "{}\t{}\t{}",
            frontend.id,
            frontend.name,
            frontend.launch.kind()
        );
    }
    ExitCode::SUCCESS
}

/// The config directory alone: `list` reads the registry and never contacts the service, so a
/// runtime-directory fallback is no concern of it and its warning is not printed.
fn config_dir() -> Option<PathBuf> {
    match ServiceDirs::resolve_env() {
        Ok(resolution) => Some(resolution.dirs.config_dir),
        Err(error) => {
            eprintln!("stt-app: {error}");
            None
        }
    }
}

/// The per-user directories, or a message naming the variable that is missing.
fn dirs() -> Option<ServiceDirs> {
    match ServiceDirs::from_env() {
        Ok(dirs) => Some(dirs),
        Err(error) => {
            eprintln!("stt-app: {error}");
            None
        }
    }
}

/// The installed frontends; a user with none yet is not an error.
fn registry(config_dir: &Path) -> Registry {
    let frontends = config_dir.join("frontends");
    if frontends.is_dir() {
        match read_registry(&frontends) {
            Ok(registry) => registry,
            Err(error) => {
                eprintln!("stt-app: cannot read {}: {error}", frontends.display());
                Registry {
                    frontends: Vec::new(),
                    skipped: Vec::new(),
                }
            }
        }
    } else {
        Registry {
            frontends: Vec::new(),
            skipped: Vec::new(),
        }
    }
}

/// The descriptor `args` names, with the installed ids in the message when it names none or an
/// unknown one.
fn select<'a>(
    located: &mut Located,
    registry: &'a Registry,
    args: &[String],
) -> Option<&'a Descriptor> {
    let installed = || registry.ids().join(", ");
    let id = match args.iter().find(|arg| !arg.starts_with("--")) {
        Some(id) => id.to_string(),
        None => match located
            .service()
            .and_then(|service| default_frontend(service).map_err(|error| error.to_string()))
        {
            Ok(Some(id)) => id,
            Ok(None) => {
                eprintln!(
                    "stt-app: no default frontend is set (installed: {})",
                    installed()
                );
                return None;
            }
            Err(error) => {
                eprintln!("stt-app: cannot read the default frontend: {error}");
                return None;
            }
        },
    };
    match registry.find(&id) {
        Some(descriptor) => Some(descriptor),
        None => {
            eprintln!(
                "stt-app: no frontend {id} is registered (installed: {})",
                installed()
            );
            None
        }
    }
}

/// The running service, located at most once per command and only when a step needs it: every
/// `locate` asks the service's health, and a frontend opened by id that is not the browser never
/// contacts the service at all. It keeps whether this command started the service, which decides
/// whether it also starts the tray (ADR 0064).
struct Located<'a> {
    dirs: &'a ServiceDirs,
    service: Option<Result<StartOrAttach, String>>,
}

impl<'a> Located<'a> {
    fn new(dirs: &'a ServiceDirs) -> Self {
        Located {
            dirs,
            service: None,
        }
    }

    /// The service, or why it could not be found, as the first call found it.
    fn service(&mut self) -> Result<&Service, String> {
        if self.service.is_none() {
            self.service = Some(
                locate(self.dirs)
                    .map(|service| StartOrAttach {
                        service,
                        started: false,
                    })
                    .map_err(|error| error.to_string()),
            );
        }
        self.answer()
    }

    /// Like [`service`](Self::service), but brings the unit up (ADR 0064): the service through the
    /// runner beside `stt-app` when none answers (decision (b)), and the tray only when this launch
    /// started the service. A failed tray start stops that service again and fails the open.
    fn unit_or_start(&mut self) -> Result<&Service, String> {
        if !matches!(self.service, Some(Ok(_))) {
            let dirs = self.dirs;
            self.service = Some(
                ensure_unit(
                    dirs,
                    || start_runner_beside(dirs),
                    || start_tray_beside(dirs),
                )
                .map_err(|error| error.to_string()),
            );
        }
        self.answer()
    }

    fn answer(&self) -> Result<&Service, String> {
        self.service
            .as_ref()
            .expect("filled by the callers")
            .as_ref()
            .map(|run| &run.service)
            .map_err(Clone::clone)
    }
}

/// Starts `program` with `args`, detached from this command.
///
/// The frontend outlives this command, so it must not inherit its stdio: on Windows a grandchild
/// holding the pipe keeps a caller's capture open, and a caller reading the output (`stt-app open
/// window | ...`, the e2e harness) would wait forever.
fn spawn(program: &str, args: &[String]) -> ExitCode {
    match Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(_) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("stt-app: could not start {program}: {error}");
            ExitCode::FAILURE
        }
    }
}

/// The host OS as the launchers name it.
fn host_os() -> Os {
    Os::from(std::env::consts::OS)
}

/// Whether `program` is somewhere on `PATH`.
fn on_path(program: &str) -> bool {
    std::env::var_os("PATH")
        .map(|paths| std::env::split_paths(&paths).any(|dir| dir.join(program).is_file()))
        .unwrap_or(false)
}
