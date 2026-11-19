//! `stt-tray`, the tray of the per-user service (roadmap item 66, ADR 0064). The service and the
//! tray are one unit: the tray watches the service it belongs to, restarts it through the runner
//! beside it when it died, and exits when it cannot - so the unit is never left half up. With the
//! `tray-menu` feature it shows an icon and a menu; without it, or where no tray host exists, it
//! runs virtually (ADR 0064).

use std::process::ExitCode;

use stt_client::ServiceDirs;

#[cfg(feature = "tray-menu")]
mod tray_menu;

fn main() -> ExitCode {
    let dirs = match ServiceDirs::from_env() {
        Ok(dirs) => dirs,
        Err(error) => {
            eprintln!("stt-tray: {error}");
            return ExitCode::FAILURE;
        }
    };
    #[cfg(feature = "tray-menu")]
    {
        tray_menu::run(&dirs)
    }
    #[cfg(not(feature = "tray-menu"))]
    {
        supervise_virtually(&dirs)
    }
}

/// The tray without an icon ([`stt_client::supervise_until_stopped`]) until the unit stops.
fn supervise_virtually(dirs: &ServiceDirs) -> ExitCode {
    block_on(stt_client::supervise_until_stopped(dirs)).unwrap_or_else(|error| {
        eprintln!("stt-tray: {error}");
        ExitCode::FAILURE
    })
}

/// Runs `future` to its end on a runtime of its own on the calling thread. The tray's event loop
/// owns the main thread, so each piece of the tray's async work - the supervision on its thread, a
/// quit from the menu - drives its own.
fn block_on<F: Future>(future: F) -> std::io::Result<F::Output> {
    Ok(tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?
        .block_on(future))
}
