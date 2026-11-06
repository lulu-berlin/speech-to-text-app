//! The tray's icon and menu (roadmap item 66, test-list items 2 and 3; ADR 0064), built with
//! `tray-icon` and `muda` on the `tray-menu` feature. Without the feature `stt-tray` runs virtually -
//! the same supervision, no icon - which is the state ADR 0064 allows where no tray host exists.
//!
//! The tray owns the main thread, as macOS demands, and its supervision runs on a background thread;
//! a supervision that stops the unit ends the process. The menu follows the registry: a frontend
//! registered (or removed) while the tray runs appears without restarting it.

use std::process::{Command, ExitCode};
use std::thread;

use tao::event::Event;
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, TrayIconBuilder};

use stt_client::{Registry, ServiceDirs, Supervisor, Watch, quit, tray_entries};

/// What the tray's event loop listens for: everything arrives as a user event, because both the
/// menu, the supervision thread and the registry watcher live outside it and must wake it (a menu
/// click does not wake a waiting `tao` loop by itself, and a waiting loop never polls a channel).
enum TrayEvent {
    /// A menu item was clicked: `open:<id>` or `quit`.
    Menu(String),
    /// Supervision gave up: the unit is both down, so the icon goes too.
    Stopped(String),
    /// The service stopped on purpose (`stt-app quit`), which the tray honours quietly.
    UnitStopped,
    /// A frontend was installed or removed: rebuild the menu.
    RegistryChanged,
}

/// Runs the tray with its icon and menu until the unit stops or the user quits. [dirs] are the
/// service's, for the registry and the quit request.
pub fn run(dirs: &ServiceDirs) -> ExitCode {
    // A display is what the icon needs, and the event loop itself cannot even be created without one
    // (tao panics, before anything here could catch it). So the headless case is decided first and
    // runs the virtual tray ADR 0064 allows - the state a shell session and a CI runner are in.
    if !has_a_screen() {
        eprintln!("stt-tray: no display; supervising without an icon");
        return stt_client::supervise_until_stopped(dirs);
    }
    let event_loop = EventLoopBuilder::<TrayEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    // Menu clicks arrive on the tray host's thread; push them into the loop so it wakes up.
    let menus = proxy.clone();
    MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
        let _ = menus.send_event(TrayEvent::Menu(event.id.0.clone()));
    }));

    // Kept alive for the life of the process: the icon vanishes when it is dropped. The tray host
    // layer panics rather than returning an error when its native library is missing (libappindicator
    // dlopens it), so both outcomes degrade to the virtual tray ADR 0064 allows.
    let registry = stt_client::registry_for(dirs);
    let built = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        TrayIconBuilder::new()
            .with_menu(Box::new(menu_for(&registry)))
            .with_tooltip("Speech to Text")
            .with_icon(icon())
            .build()
    }));
    let tray = match built {
        Ok(Ok(tray)) => tray,
        Ok(Err(error)) => {
            eprintln!("stt-tray: no tray host ({error}); supervising without an icon");
            MenuEvent::set_event_handler::<fn(MenuEvent)>(None);
            return stt_client::supervise_until_stopped(dirs);
        }
        Err(_) => {
            eprintln!("stt-tray: no tray host library; supervising without an icon");
            MenuEvent::set_event_handler::<fn(MenuEvent)>(None);
            return stt_client::supervise_until_stopped(dirs);
        }
    };

    let supervised = dirs.clone();
    let stopper = proxy.clone();
    thread::spawn(move || {
        let mut supervisor = Supervisor::new();
        let mut registered = false;
        loop {
            match stt_client::watch_and_register(&mut supervisor, &supervised, &mut registered) {
                Watch::Stopped => {
                    let _ = stopper.send_event(TrayEvent::UnitStopped);
                    return;
                }
                Watch::Stop(error) => {
                    let _ = stopper.send_event(TrayEvent::Stopped(error.to_string()));
                    return;
                }
                Watch::Alive | Watch::Restarted => thread::sleep(stt_client::LOOK_INTERVAL),
            }
        }
    });

    // The registry is install-time data: watch it so a frontend installed while the tray runs shows
    // up in the menu without restarting the tray.
    let watched = dirs.clone();
    let changed = proxy.clone();
    thread::spawn(move || {
        let mut seen = stt_client::registry_fingerprint(&watched);
        loop {
            thread::sleep(stt_client::LOOK_INTERVAL);
            let now = stt_client::registry_fingerprint(&watched);
            if now != seen {
                seen = now;
                if changed.send_event(TrayEvent::RegistryChanged).is_err() {
                    return;
                }
            }
        }
    });

    // The event loop's closure lives for the process, so it owns its copy of the directories.
    let owned = dirs.clone();
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(TrayEvent::Menu(id)) if id == "quit" => {
                let _ = quit(&owned);
                *control_flow = ControlFlow::Exit;
            }
            Event::UserEvent(TrayEvent::Menu(id)) => {
                if let Some(frontend) = id.strip_prefix("open:") {
                    open_frontend(frontend);
                }
            }
            Event::UserEvent(TrayEvent::RegistryChanged) => {
                let registry = stt_client::registry_for(&owned);
                tray.set_menu(Some(Box::new(menu_for(&registry))));
            }
            Event::UserEvent(TrayEvent::Stopped(reason)) => {
                eprintln!("stt-tray: {reason}; the tray stops too");
                *control_flow = ControlFlow::ExitWithCode(1);
            }
            Event::UserEvent(TrayEvent::UnitStopped) => {
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
}

/// Whether this session has a display to put an icon on. Linux needs one of the two display
/// variables; macOS and Windows always have a screen.
fn has_a_screen() -> bool {
    if cfg!(target_os = "linux") {
        std::env::var_os("DISPLAY").is_some() || std::env::var_os("WAYLAND_DISPLAY").is_some()
    } else {
        true
    }
}

/// The tray's menu for [registry]: one entry per installed frontend (the default marked), a
/// separator and Quit. Rebuilt whenever the registry changes.
fn menu_for(registry: &Registry) -> Menu {
    let menu = Menu::new();
    for entry in tray_entries(registry, None) {
        let label = if entry.is_default {
            format!("Open {} (default)", entry.name)
        } else {
            format!("Open {}", entry.name)
        };
        let item = MenuItem::with_id(format!("open:{}", entry.id), &label, true, None);
        menu.append(&item).expect("the menu accepts its items");
    }
    let quit_item = MenuItem::with_id("quit", "Quit", true, None);
    let _ = menu.append(&PredefinedMenuItem::separator());
    menu.append(&quit_item)
        .expect("the menu accepts its last item");
    menu
}

/// Opens [frontend] through the `stt-app` beside the tray: the same start-or-attach, launch-URL and
/// terminal chain a user gets from the command line.
fn open_frontend(frontend: &str) {
    let Ok(exe) = std::env::current_exe() else {
        eprintln!("stt-tray: cannot find stt-app beside the tray");
        return;
    };
    let cli = exe.with_file_name(format!("stt-app{}", std::env::consts::EXE_SUFFIX));
    match Command::new(&cli).args(["open", frontend]).spawn() {
        Ok(_) => {}
        Err(error) => eprintln!("stt-tray: could not open {frontend}: {error}"),
    }
}

/// A 32x32 icon: a filled circle, drawn here so the crate needs no binary asset.
fn icon() -> Icon {
    const SIZE: u32 = 32;
    let mut rgba = vec![0u8; (SIZE * SIZE * 4) as usize];
    let centre = (SIZE as f32 - 1.0) / 2.0;
    for y in 0..SIZE {
        for x in 0..SIZE {
            let dx = x as f32 - centre;
            let dy = y as f32 - centre;
            if dx * dx + dy * dy <= (centre - 1.0) * (centre - 1.0) {
                let at = ((y * SIZE + x) * 4) as usize;
                rgba[at..at + 4].copy_from_slice(&[58, 110, 165, 255]);
            }
        }
    }
    Icon::from_rgba(rgba, SIZE, SIZE).expect("32x32 RGBA is a valid icon")
}
