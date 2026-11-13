//! Finding, controlling and tuning the sync engine on this computer.

use std::path::PathBuf;
use std::process::Command;

pub const TANDEM_UNIT: &str = "tandem-engine.service";
pub const FEDORA_UNIT: &str = "syncthing.service";
pub const ADDRESS: &str = "http://127.0.0.1:8384";

pub fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/".into()))
}

/// Syncthing's config + identity folder (shared with a previous Syncthing install, on purpose:
/// Tandem takes over the same identity so paired devices keep working).
pub fn state_dir() -> PathBuf {
    let base = std::env::var("XDG_STATE_HOME").map(PathBuf::from).unwrap_or_else(|_| home().join(".local/state"));
    base.join("syncthing")
}

/// The systemd user service that runs the engine: Tandem's own once installed, otherwise the
/// distribution's Syncthing (so the app is useful before switching over).
pub fn unit() -> &'static str {
    let ours = home().join(".config/systemd/user").join(TANDEM_UNIT);
    if ours.exists() { TANDEM_UNIT } else { FEDORA_UNIT }
}

pub fn is_tandem_engine() -> bool {
    unit() == TANDEM_UNIT
}

pub struct Conn {
    pub base: String,
    pub key: String,
}

/// Reads the API address and key from the engine's config file.
pub fn connection() -> Option<Conn> {
    let xml = std::fs::read_to_string(state_dir().join("config.xml")).ok()?;
    let doc = roxmltree::Document::parse(&xml).ok()?;
    let gui = doc.descendants().find(|n| n.has_tag_name("gui"))?;
    let key = gui.children().find(|n| n.has_tag_name("apikey"))?.text()?.trim().to_string();
    let base = if is_tandem_engine() {
        ADDRESS.to_string() // our service pins this address
    } else {
        let addr = gui.children().find(|n| n.has_tag_name("address"))?.text()?.trim().to_string();
        let tls = gui.attribute("tls") == Some("true");
        format!("{}://{}", if tls { "https" } else { "http" }, addr)
    };
    Some(Conn { base, key })
}

fn systemctl(args: &[&str]) -> (bool, String) {
    match Command::new("systemctl").arg("--user").args(args).output() {
        Ok(o) => (o.status.success(), String::from_utf8_lossy(&o.stdout).trim().to_string()),
        Err(e) => (false, e.to_string()),
    }
}

pub fn state() -> String {
    systemctl(&["is-active", unit()]).1
}

pub fn enabled() -> bool {
    systemctl(&["is-enabled", unit()]).0
}

pub fn set_running(on: bool) -> Result<(), String> {
    let (ok, out) = systemctl(&[if on { "start" } else { "stop" }, unit()]);
    if ok { Ok(()) } else { Err(out) }
}

pub fn set_enabled(on: bool) -> Result<(), String> {
    let (ok, out) = systemctl(&[if on { "enable" } else { "disable" }, unit()]);
    if ok { Ok(()) } else { Err(out) }
}

/// Real memory used by the engine: the sum of resident memory of every process in its service.
pub fn memory_bytes() -> Option<u64> {
    let cg = systemctl(&["show", "-p", "ControlGroup", "--value", unit()]).1;
    if cg.is_empty() {
        return None;
    }
    let procs = std::fs::read_to_string(format!("/sys/fs/cgroup{cg}/cgroup.procs")).ok()?;
    let mut total = 0u64;
    for pid in procs.lines() {
        if let Ok(status) = std::fs::read_to_string(format!("/proc/{pid}/status")) {
            if let Some(line) = status.lines().find(|l| l.starts_with("VmRSS:")) {
                let kb: u64 = line.split_whitespace().nth(1).and_then(|v| v.parse().ok()).unwrap_or(0);
                total += kb * 1024;
            }
        }
    }
    (total > 0).then_some(total)
}

pub fn log_tail() -> String {
    match Command::new("journalctl").args(["--user", "-u", unit(), "-n", "300", "--no-pager", "-o", "short-iso"]).output() {
        Ok(o) => {
            let t = String::from_utf8_lossy(&o.stdout).to_string();
            if t.trim().is_empty() { "No log yet.".into() } else { t }
        }
        Err(e) => format!("Couldn't read the log: {e}"),
    }
}

pub fn copy_to_clipboard(text: &str) -> bool {
    use std::io::Write;
    for (cmd, args) in [("wl-copy", vec![]), ("xclip", vec!["-selection", "clipboard"])] {
        if let Ok(mut child) = Command::new(cmd).args(&args).stdin(std::process::Stdio::piped()).spawn() {
            if let Some(mut stdin) = child.stdin.take() {
                let _ = stdin.write_all(text.as_bytes());
            }
            return child.wait().map(|s| s.success()).unwrap_or(false);
        }
    }
    false
}

pub fn open_path(path: &str) {
    let _ = Command::new("xdg-open").arg(path).spawn();
}
