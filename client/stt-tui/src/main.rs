//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself.

use std::process::ExitCode;

use stt_client::{ServiceDirs, locate};

#[tokio::main(flavor = "current_thread")]
async fn main() -> ExitCode {
    let dirs = match ServiceDirs::from_env() {
        Ok(dirs) => dirs,
        Err(error) => {
            eprintln!("stt-tui: {error}");
            return ExitCode::FAILURE;
        }
    };
    match locate(&dirs).await {
        Ok(_service) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!(
                "stt-tui: the service is not running - start it with `stt-app open` ({error})"
            );
            ExitCode::FAILURE
        }
    }
}
