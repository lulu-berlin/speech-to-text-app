//! Roadmap item 66, test list item 2 (the browser half): the browser frontend opens in the OS
//! default browser, through each OS's own opener — `xdg-open` on Linux, `open` on macOS, and the
//! shell's default-browser association (`explorer`) on Windows. The launch URL is one argument,
//! never passed through a shell, so its `?t=` query reaches the browser as it is. The real launch
//! on each OS is proven by that OS's CI leg (test list item 10).

use stt_client::{LaunchCommand, Os, browser_command};

const URL: &str = "http://127.0.0.1:28763/open?t=one-time";

#[test]
fn the_browser_frontend_opens_with_each_os_default_browser_opener() {
    assert_eq!(
        browser_command(URL, Os::Linux),
        LaunchCommand {
            program: "xdg-open".into(),
            args: vec![URL.into()],
        }
    );
    assert_eq!(
        browser_command(URL, Os::MacOs),
        LaunchCommand {
            program: "open".into(),
            args: vec![URL.into()],
        }
    );
    assert_eq!(
        browser_command(URL, Os::Windows),
        LaunchCommand {
            program: "explorer".into(),
            args: vec![URL.into()],
        }
    );
}
