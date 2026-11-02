//! `stt-tray`, the tray of the per-user service (roadmap item 66, ADR 0064). The service and the
//! tray are one unit: the tray watches the service it belongs to, restarts it through the runner
//! beside it when it died, and exits when it cannot - so the unit is never left half up. With the
//! `tray-menu` feature it shows an icon and a menu; without it, or where no tray host exists, it
//! runs virtually (ADR 0064).

use std::process::ExitCode;

use stt_client::ServiceDirs;

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
        stt_client::tray_menu::run(&dirs)
    }
    #[cfg(not(feature = "tray-menu"))]
    {
        stt_client::supervise_until_stopped(&dirs)
    }
}
