//! Roadmap item 66, ADR 0064 (the tray's half of the mutual supervision): the tray watches the
//! service it belongs to. A live service is left alone; a dead one is restarted; and when the
//! restart fails, the tray stops too, so the unit is never left half up. A crash loop counts as a
//! failed restart: at most 3 restarts within 60 seconds.

mod support;

use std::io;
use std::path::Path;
use std::time::{Duration, Instant};

use stt_client::{Supervisor, Watch, watch_service};

#[test]
fn the_tray_restarts_a_dead_service_and_stops_when_the_restart_fails() {
    // A live service: nothing to do.
    let live = tempfile::tempdir().unwrap();
    support::start_and_answer(live.path());
    let watch = watch_service(&support::dirs_in(live.path()), || {
        panic!("a live service must not be restarted")
    });
    assert!(matches!(watch, Watch::Alive), "{watch:?}");

    // A dead service - its runtime.json points at a port nothing listens on - comes back.
    let dead = tempfile::tempdir().unwrap();
    support::write_bootstrap(dead.path(), support::closed_port());
    let watch = watch_service(&support::dirs_in(dead.path()), || {
        support::start_and_answer(dead.path());
        Ok(())
    });
    assert!(matches!(watch, Watch::Restarted), "{watch:?}");

    // A dead service that cannot be restarted: the tray stops too, with the reason.
    let lost = tempfile::tempdir().unwrap();
    support::write_bootstrap(lost.path(), support::closed_port());
    let watch = watch_service(&support::dirs_in(lost.path()), || {
        Err(io::Error::other("no stt-service beside stt-tray"))
    });
    match watch {
        Watch::Stop(error) => assert!(
            error.to_string().contains("no stt-service beside stt-tray"),
            "{error}"
        ),
        other => panic!("a failed restart must stop the tray, got {other:?}"),
    }
}

#[test]
fn a_service_that_dies_a_fourth_time_within_60_seconds_stops_the_tray() {
    let start = Instant::now();

    // Three deaths within the window are restarted; the fourth is a crash loop.
    let looping = tempfile::tempdir().unwrap();
    let mut supervisor = Supervisor::new();
    for seconds in [0, 10, 20] {
        let watch = die_and_watch(&mut supervisor, looping.path(), start + secs(seconds));
        assert!(
            matches!(watch, Watch::Restarted),
            "death at {seconds}s: {watch:?}"
        );
    }
    match die_and_watch(&mut supervisor, looping.path(), start + secs(30)) {
        Watch::Stop(error) => assert!(error.to_string().contains("3 times"), "{error}"),
        other => panic!("a fourth death within 60 seconds must stop the tray, got {other:?}"),
    }

    // Deaths spread wider than the window keep being restarted.
    let spread = tempfile::tempdir().unwrap();
    let mut supervisor = Supervisor::new();
    for seconds in [0, 10, 20, 70] {
        let watch = die_and_watch(&mut supervisor, spread.path(), start + secs(seconds));
        assert!(
            matches!(watch, Watch::Restarted),
            "death at {seconds}s: {watch:?}"
        );
    }
}

/// Lets the service in `dir` die - its runtime.json now points at a port nothing listens on - then
/// has `supervisor` look at it at `now`, restarting it as a stand-in that answers.
fn die_and_watch(supervisor: &mut Supervisor, dir: &Path, now: Instant) -> Watch {
    support::write_bootstrap(dir, support::closed_port());
    supervisor.watch(&support::dirs_in(dir), now, || {
        support::start_and_answer(dir);
        Ok(())
    })
}

fn secs(seconds: u64) -> Duration {
    Duration::from_secs(seconds)
}
