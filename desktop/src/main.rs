// Tandem for the desktop: a small window over the sync engine. Closing it frees everything;
// the engine keeps running as its own background service.
#![cfg_attr(windows, windows_subsystem = "windows")]

mod api;
mod engine;

use api::{Api, Device, Folder, Snapshot};
use slint::{Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, SharedString, VecModel};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

slint::include_modules!();

enum Cmd {
    Sync(bool),
    Autostart(bool),
    AcceptDevice(String, String),
    DismissDevice(String),
    AcceptFolder { id: String, label: String, device: String, path: String },
    DismissFolder(String, String),
    AddDevice(String, String),
    AddFolder { label: String, path: String, devices: Vec<String> },
    Pause(String, bool),
    Rescan(String),
    Share(String, Vec<String>),
    RemoveFolder(String),
    RemoveDevice(String),
    Rename(String, String),
}

#[derive(Default)]
struct Shared {
    snap: Option<Snapshot>,
    folder: String,
    device: String,
}

fn main() -> Result<(), slint::PlatformError> {
    let _ = slint::set_xdg_app_id("io.github.samsyk.Tandem");
    let ui = AppWindow::new()?;
    let shared = Arc::new(Mutex::new(Shared::default()));
    let (tx, rx) = mpsc::channel::<Cmd>();

    wire(&ui, &shared, &tx);
    // Developer aid for screenshots: TANDEM_PAGE=<n> opens a screen directly.
    if let Some(p) = std::env::var("TANDEM_PAGE").ok().and_then(|p| p.parse().ok()) {
        ui.set_page(p);
    }

    let weak = ui.as_weak();
    let sh = shared.clone();
    std::thread::spawn(move || worker(rx, weak, sh));

    ui.run()
}

fn wire(ui: &AppWindow, shared: &Arc<Mutex<Shared>>, tx: &mpsc::Sender<Cmd>) {
    let send = |tx: &mpsc::Sender<Cmd>| {
        let tx = tx.clone();
        move |c: Cmd| { let _ = tx.send(c); }
    };

    let s = send(tx);
    let w = ui.as_weak();
    ui.on_headline_clicked(move |action| match action.as_str() {
        "Pair a device" => { if let Some(ui) = w.upgrade() { ui.set_page(1); } }
        _ => s(Cmd::Sync(true)),
    });

    let s = send(tx);
    ui.on_toggle_sync(move |on| s(Cmd::Sync(on)));
    let s = send(tx);
    ui.on_set_autostart(move |on| s(Cmd::Autostart(on)));
    let s = send(tx);
    ui.on_accept_device(move |id, name| s(Cmd::AcceptDevice(id.into(), name.into())));
    let s = send(tx);
    ui.on_dismiss_device(move |id| s(Cmd::DismissDevice(id.into())));
    let s = send(tx);
    ui.on_accept_folder(move |id, label, device, path| {
        let path = if path.is_empty() {
            engine::home().join(safe_name(&label)).to_string_lossy().to_string()
        } else { path.into() };
        s(Cmd::AcceptFolder { id: id.into(), label: label.into(), device: device.into(), path })
    });
    let s = send(tx);
    ui.on_dismiss_folder(move |id, dev| s(Cmd::DismissFolder(id.into(), dev.into())));
    let s = send(tx);
    let w = ui.as_weak();
    ui.on_add_device(move |id, name| {
        let name = if name.trim().is_empty() { "My phone".to_string() } else { name.trim().to_string() };
        s(Cmd::AddDevice(id.into(), name));
        if let Some(ui) = w.upgrade() { ui.set_page(0); }
    });
    let s = send(tx);
    let w = ui.as_weak();
    ui.on_add_folder(move |label, path| {
        let Some(ui) = w.upgrade() else { return };
        let devices = checked(&ui);
        s(Cmd::AddFolder { label: label.trim().into(), path: path.into(), devices });
        ui.set_page(0);
    });
    let s = send(tx);
    ui.on_folder_pause(move |id, paused| s(Cmd::Pause(id.into(), paused)));
    let s = send(tx);
    ui.on_folder_rescan(move |id| s(Cmd::Rescan(id.into())));
    let s = send(tx);
    let w = ui.as_weak();
    ui.on_folder_share(move |id| {
        let Some(ui) = w.upgrade() else { return };
        s(Cmd::Share(id.into(), checked(&ui)));
    });
    ui.on_folder_show(|path| engine::open_path(&path));
    let s = send(tx);
    let w = ui.as_weak();
    ui.on_confirmed(move |action| {
        if let Some((kind, id)) = action.split_once(':') {
            match kind {
                "remove-folder" => s(Cmd::RemoveFolder(id.into())),
                "remove-device" => s(Cmd::RemoveDevice(id.into())),
                _ => {}
            }
            if let Some(ui) = w.upgrade() { ui.set_page(0); }
        }
    });
    let s = send(tx);
    let sh = shared.clone();
    ui.on_rename(move |target, name| {
        let id = if target == "self" {
            sh.lock().unwrap().snap.as_ref().map(|s| s.my_id.clone()).unwrap_or_default()
        } else { target.into() };
        if !id.is_empty() { s(Cmd::Rename(id, name.trim().into())); }
    });
    let w = ui.as_weak();
    ui.on_copy_text(move |text| {
        let ok = engine::copy_to_clipboard(&text);
        if let Some(ui) = w.upgrade() { ui.set_toast(if ok { "Copied".into() } else { "Couldn't reach the clipboard".into() }); }
    });
    let w = ui.as_weak();
    ui.on_load_log(move || {
        let w = w.clone();
        std::thread::spawn(move || {
            let text = engine::log_tail();
            let _ = w.upgrade_in_event_loop(move |ui| ui.set_log_text(text.into()));
        });
    });
    let w = ui.as_weak();
    ui.on_pick_folder(move |target| {
        let w = w.clone();
        let target = target.to_string();
        std::thread::spawn(move || {
            let picked = rfd::FileDialog::new().set_title("Choose a folder").set_directory(engine::home()).pick_folder();
            if let Some(p) = picked {
                let path = p.to_string_lossy().to_string();
                let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                let _ = w.upgrade_in_event_loop(move |ui| {
                    if target == "add" {
                        ui.set_add_path(path.into());
                        if ui.get_add_label().is_empty() { ui.set_add_label(name.into()); }
                    } else {
                        ui.set_accept_path(path.into());
                    }
                });
            }
        });
    });

    let sh = shared.clone();
    let w = ui.as_weak();
    ui.on_open_folder(move |id| {
        sh.lock().unwrap().folder = id.to_string();
        if let Some(ui) = w.upgrade() { push_current(&ui, &sh.lock().unwrap()); ui.set_page(2); }
    });
    let sh = shared.clone();
    let w = ui.as_weak();
    ui.on_open_device(move |id| {
        sh.lock().unwrap().device = id.to_string();
        if let Some(ui) = w.upgrade() { push_current(&ui, &sh.lock().unwrap()); ui.set_page(3); }
    });
    // Devices offered in "share with": all for a new folder, or the ones a folder isn't shared with yet.
    let sh = shared.clone();
    let w = ui.as_weak();
    ui.on_prepare_choices(move |folder_id| {
        let Some(ui) = w.upgrade() else { return };
        let st = sh.lock().unwrap();
        let Some(snap) = st.snap.as_ref() else { return };
        let skip: Vec<String> = snap.folders.iter().find(|f| f.id == folder_id.as_str()).map(|f| f.device_ids.clone()).unwrap_or_default();
        let items: Vec<Choice> = snap.devices.iter().filter(|d| !skip.contains(&d.id))
            .map(|d| Choice { id: d.id.clone().into(), name: d.name.clone().into(), checked: folder_id.is_empty() })
            .collect();
        ui.set_choices(ModelRc::new(VecModel::from(items)));
    });
}

fn checked(ui: &AppWindow) -> Vec<String> {
    ui.get_choices().iter().filter(|c| c.checked).map(|c| c.id.to_string()).collect()
}

fn safe_name(s: &str) -> String {
    let t: String = s.chars().map(|c| if "/\\:*?\"<>|".contains(c) { '_' } else { c }).collect();
    let t = t.trim().to_string();
    if t.is_empty() { "Shared folder".into() } else { t }
}

// ───────────────────────── worker ─────────────────────────

fn worker(rx: mpsc::Receiver<Cmd>, weak: slint::Weak<AppWindow>, shared: Arc<Mutex<Shared>>) {
    let mut api: Option<Api> = None;
    let mut tuned = false;
    let mut qr_for = String::new();
    loop {
        let cmd = match rx.recv_timeout(Duration::from_secs(2)) {
            Ok(c) => Some(c),
            Err(RecvTimeoutError::Timeout) => None,
            Err(RecvTimeoutError::Disconnected) => return,
        };

        // (Re)connect: the key and address come from the engine's own config.
        if api.as_ref().map(|a| !a.health()).unwrap_or(true) {
            api = engine::connection().map(|c| Api::new(c.base, c.key)).filter(|a| a.health());
            tuned = false;
        }

        if let Some(cmd) = cmd {
            let msg = run(cmd, api.as_ref());
            if let Some(m) = msg {
                let _ = weak.upgrade_in_event_loop(move |ui| ui.set_toast(m.into()));
            }
            if api.is_none() {
                std::thread::sleep(Duration::from_millis(800));
                api = engine::connection().map(|c| Api::new(c.base, c.key)).filter(|a| a.health());
            }
        }

        if let (Some(a), false) = (api.as_ref(), tuned) {
            if engine::is_tandem_engine() { tuned = a.apply_light_settings().is_ok(); } else { tuned = true; }
        }

        let state = engine::state();
        let autostart = engine::enabled();
        let mem = engine::memory_bytes();
        let snap = api.as_ref().and_then(|a| a.snapshot().ok());
        let qr = match &snap {
            Some(s) if s.my_id != qr_for => { qr_for = s.my_id.clone(); Some(qr_pixels(&s.my_id)) }
            _ => None,
        };
        shared.lock().unwrap().snap = snap.clone();
        let sh = shared.clone();
        let _ = weak.upgrade_in_event_loop(move |ui| {
            push(&ui, snap.as_ref(), &state, autostart, mem);
            if let Some(px) = qr { ui.set_qr(Image::from_rgba8(px)); }
            push_current(&ui, &sh.lock().unwrap());
        });
    }
}

fn run(cmd: Cmd, api: Option<&Api>) -> Option<String> {
    let need = || api.ok_or_else(|| "Turn sync on first".to_string());
    let result: Result<Option<String>, String> = (|| match cmd {
        Cmd::Sync(on) => engine::set_running(on).map(|_| Some(if on { "Starting sync…" } else { "Sync is off" }.into())),
        Cmd::Autostart(on) => engine::set_enabled(on).map(|_| None),
        Cmd::AcceptDevice(id, name) => need()?.add_device(&id, &name).map(|_| Some(format!("{name} is paired"))),
        Cmd::DismissDevice(id) => need()?.delete(&format!("/rest/cluster/pending/devices?device={}", api::enc(&id))).map(|_| None),
        Cmd::AcceptFolder { id, label, device, path } => need()?.create_folder(&id, &label, &path, &[device]).map(|_| Some(format!("Syncing “{label}”"))),
        Cmd::DismissFolder(id, dev) => need()?
            .delete(&format!("/rest/cluster/pending/folders?folder={}&device={}", api::enc(&id), api::enc(&dev)))
            .map(|_| None),
        Cmd::AddDevice(id, name) => need()?.add_device(&id, &name).map(|_| Some(format!("Added {name}. Accept this computer on that device."))),
        Cmd::AddFolder { label, path, devices } => need()?.create_folder(&new_folder_id(), &label, &path, &devices).map(|_| Some(format!("Syncing “{label}”"))),
        Cmd::Pause(id, p) => need()?.edit_folder(&id, |f| f["paused"] = serde_json::json!(p)).map(|_| None),
        Cmd::Rescan(id) => need()?.post(&format!("/rest/db/scan?folder={}", api::enc(&id)), None).map(|_| Some("Checking for changes…".into())),
        Cmd::Share(id, devs) => need()?
            .edit_folder(&id, |f| {
                let arr = f["devices"].as_array_mut();
                if let Some(arr) = arr {
                    for d in &devs { arr.push(serde_json::json!({"deviceID": d, "introducedBy": "", "encryptionPassword": ""})); }
                }
            })
            .map(|_| Some("Shared. Accept it on the other device.".into())),
        Cmd::RemoveFolder(id) => need()?.delete(&format!("/rest/config/folders/{}", api::enc(&id))).map(|_| Some("Stopped syncing. Your files are still here.".into())),
        Cmd::RemoveDevice(id) => need()?.delete(&format!("/rest/config/devices/{}", api::enc(&id))).map(|_| Some("Device removed".into())),
        Cmd::Rename(id, name) => need()?.rename_device(&id, &name).map(|_| None),
    })();
    match result {
        Ok(m) => m,
        Err(e) => Some(format!("That didn't work: {}", e.chars().take(160).collect::<String>())),
    }
}

fn new_folder_id() -> String {
    let a = b"abcdefghijkmnopqrstuvwxyz234567";
    let mut seed = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(7) ^ (std::process::id() as u64) << 32;
    let mut part = || {
        (0..5).map(|_| {
            seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17;
            a[(seed % a.len() as u64) as usize] as char
        }).collect::<String>()
    };
    format!("{}-{}", part(), part())
}

fn qr_pixels(text: &str) -> SharedPixelBuffer<Rgba8Pixel> {
    let code = qrcode::QrCode::new(text.as_bytes()).expect("qr");
    let w = code.width();
    let colors = code.to_colors();
    let (scale, quiet) = (8usize, 2usize);
    let size = (w + quiet * 2) * scale;
    let mut buf = SharedPixelBuffer::<Rgba8Pixel>::new(size as u32, size as u32);
    let px = buf.make_mut_slice();
    for y in 0..size {
        for x in 0..size {
            let (mx, my) = ((x / scale) as isize - quiet as isize, (y / scale) as isize - quiet as isize);
            let dark = mx >= 0 && my >= 0 && (mx as usize) < w && (my as usize) < w && colors[my as usize * w + mx as usize] == qrcode::Color::Dark;
            let v = if dark { 0 } else { 255 };
            px[y * size + x] = Rgba8Pixel { r: v, g: v, b: v, a: 255 };
        }
    }
    buf
}

// ───────────────────────── presenting ─────────────────────────

fn plural(n: i64, one: &str) -> String { format!("{n} {one}{}", if n == 1 { "" } else { "s" }) }

fn bytes(n: i64) -> String {
    let mut v = n as f64;
    for unit in ["B", "KB", "MB", "GB"] {
        if v < 1000.0 {
            return if unit == "B" || unit == "KB" || v >= 100.0 { format!("{} {unit}", v as i64) } else { format!("{v:.1} {unit}") };
        }
        v /= 1000.0;
    }
    format!("{v:.1} TB")
}

fn ago(t: Option<i64>) -> String {
    let Some(t) = t else { return "Never connected".into() };
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(t);
    let m = (now - t).max(0) / 60;
    match m {
        0 => "Seen just now".into(),
        1..=59 => format!("Seen {m} min ago"),
        60..=1439 => format!("Seen {} h ago", m / 60),
        _ if m / 1440 < 30 => format!("Seen {} ago", plural(m / 1440, "day")),
        _ => "Not seen for over a month".into(),
    }
}

fn tilde(p: &str) -> String {
    let h = engine::home().to_string_lossy().to_string();
    if !h.is_empty() && p.starts_with(&h) { format!("~{}", &p[h.len()..]) } else { p.to_string() }
}

fn folder_state(f: &Folder) -> (&'static str, i32) {
    if f.paused { ("Paused", 4) }
    else if f.problem() { ("Problem", 3) }
    else if f.syncing() || f.state.starts_with("sync") { ("Syncing", 1) }
    else if f.scanning() { ("Checking", 1) }
    else if f.state == "idle" { ("Up to date", 0) }
    else if f.state == "unknown" { ("Starting", 4) }
    else { ("Working", 1) }
}

fn device_status(d: &Device) -> String {
    match (d.paused, d.connected, d.completion) {
        (true, _, _) => "Paused".into(),
        (_, true, Some(c)) if c < 99.95 => format!("Connected · {}% synced", c as i64),
        (_, true, _) => "Connected".into(),
        _ => ago(d.last_seen),
    }
}

fn looks_like_phone(name: &str) -> bool {
    let n = name.to_lowercase();
    ["phone", "pixel", "galaxy", "moto", "edge", "xiaomi", "redmi", "oneplus", "android", "iphone", "tablet", "ipad"].iter().any(|k| n.contains(k))
}

fn folder_item(f: &Folder, s: &Snapshot) -> FolderItem {
    let (state, tone) = folder_state(f);
    let detail = if f.problem() {
        f.error.clone().unwrap_or_else(|| format!("{} couldn't be synced. Check that the folder still exists.", plural(f.pull_errors, "file")))
    } else if f.syncing() {
        format!("{} left · {}", plural(f.need_files, "file"), bytes(f.need_bytes))
    } else if f.paused {
        "Changes aren't being synced until you resume.".into()
    } else {
        "This folder matches your other devices.".into()
    };
    let shared = f.device_ids.iter().map(|id| s.devices.iter().find(|d| &d.id == id).map(|d| d.name.clone()).unwrap_or_else(|| id.chars().take(7).collect()))
        .collect::<Vec<_>>().join(", ");
    FolderItem {
        id: f.id.clone().into(),
        label: f.label.clone().into(),
        path: tilde(&f.path).into(),
        state: state.into(),
        tone,
        detail: detail.into(),
        progress: f.progress(),
        syncing: f.syncing(),
        paused: f.paused,
        size: if f.paused { "—".into() } else { format!("{} · {}", bytes(f.global_bytes), plural(f.global_files, "file")).into() },
        shared: if shared.is_empty() { "Not shared with any device yet".into() } else { shared.into() },
    }
}

fn device_item(d: &Device, s: &Snapshot) -> DeviceItem {
    let folders = s.folders.iter().filter(|f| f.device_ids.contains(&d.id)).map(|f| f.label.clone()).collect::<Vec<_>>().join(", ");
    DeviceItem {
        id: d.id.clone().into(),
        name: d.name.clone().into(),
        status: device_status(d).into(),
        connected: d.connected,
        phone: looks_like_phone(&d.name),
        address: if d.connected { format!("{}{}", if d.relay { "Through a relay (slower) · " } else { "Direct · " }, d.address).into() } else { SharedString::new() },
        folders: if folders.is_empty() { "None yet".into() } else { folders.into() },
    }
}

fn headline(state: &str, s: Option<&Snapshot>) -> (String, String, i32, String) {
    let h = |t: &str, d: String, tone: i32, a: &str| (t.to_string(), d, tone, a.to_string());
    let Some(s) = s else {
        return match state {
            "active" | "activating" | "reloading" => h("Starting up…", "Getting the sync engine ready.".into(), 1, ""),
            "failed" => h("Sync stopped working", "The engine closed unexpectedly. Settings → View engine log shows why.".into(), 3, "Try again"),
            _ => h("Sync is off", "Your folders won't update until you turn it on.".into(), 4, "Turn on"),
        };
    };
    if let Some(f) = s.folders.iter().find(|f| f.problem()) {
        let why = f.error.clone().unwrap_or_else(|| format!("{} couldn't be synced.", plural(f.pull_errors, "file")));
        return h(&format!("Problem with “{}”", f.label), why, 3, "");
    }
    if s.devices.is_empty() {
        return h("Let's pair a device", "Connect your phone to start syncing.".into(), 4, "Pair a device");
    }
    let syncing: Vec<&Folder> = s.folders.iter().filter(|f| f.syncing()).collect();
    if !syncing.is_empty() {
        let n: i64 = syncing.iter().map(|f| f.need_files).sum();
        let b: i64 = syncing.iter().map(|f| f.need_bytes).sum();
        return h(&format!("Syncing {}", plural(n, "file")), format!("{} to go", bytes(b)), 1, "");
    }
    if s.folders.iter().any(|f| f.scanning()) {
        return h("Checking for changes…", "Looking through your folders.".into(), 1, "");
    }
    let online = s.devices.iter().filter(|d| d.connected).count() as i64;
    if online == 0 {
        return h("Your devices are offline", "Changes will sync as soon as one is reachable.".into(), 2, "");
    }
    if s.folders.is_empty() {
        return h("Paired — no folders yet", "Accept a shared folder or add one of your own.".into(), 0, "");
    }
    h(
        "Everything's in sync",
        format!("{} · {} of {} connected", plural(s.folders.len() as i64, "folder"), online, plural(s.devices.len() as i64, "device")),
        0,
        "",
    )
}

fn push(ui: &AppWindow, snap: Option<&Snapshot>, state: &str, autostart: bool, mem: Option<u64>) {
    let (title, detail, tone, action) = headline(state, snap);
    ui.set_headline_title(title.into());
    ui.set_headline_detail(detail.into());
    ui.set_headline_tone(tone);
    ui.set_headline_action(action.into());
    ui.set_engine_running(snap.is_some());
    ui.set_sync_on(matches!(state, "active" | "activating" | "reloading"));
    ui.set_autostart(autostart);
    let light = engine::is_tandem_engine();
    let mem_txt = mem.map(|m| format!(" · using {} of memory", bytes(m as i64))).unwrap_or_default();
    ui.set_engine_note(
        if light { format!("Light mode{mem_txt}") } else { format!("Your system's Syncthing (standard settings){mem_txt}") }.into(),
    );
    let Some(s) = snap else {
        ui.set_folders(ModelRc::default());
        ui.set_devices(ModelRc::default());
        ui.set_pending_devices(ModelRc::default());
        ui.set_pending_folders(ModelRc::default());
        return;
    };
    ui.set_my_id(s.my_id.clone().into());
    ui.set_my_name(s.my_name.clone().into());
    ui.set_version(s.version.clone().into());
    ui.set_folders(ModelRc::new(VecModel::from(s.folders.iter().map(|f| folder_item(f, s)).collect::<Vec<_>>())));
    ui.set_devices(ModelRc::new(VecModel::from(s.devices.iter().map(|d| device_item(d, s)).collect::<Vec<_>>())));
    ui.set_pending_devices(ModelRc::new(VecModel::from(
        s.pending_devices.iter().map(|p| PendingDev { id: p.id.clone().into(), name: p.name.clone().into(), short: p.id.chars().take(7).collect::<String>().into() }).collect::<Vec<_>>(),
    )));
    ui.set_pending_folders(ModelRc::new(VecModel::from(
        s.pending_folders.iter().map(|p| PendingFol {
            id: p.id.clone().into(),
            label: p.label.clone().into(),
            device_id: p.device_id.clone().into(),
            device_name: p.device_name.clone().into(),
        }).collect::<Vec<_>>(),
    )));
}

fn push_current(ui: &AppWindow, st: &Shared) {
    let Some(s) = st.snap.as_ref() else { return };
    // With nothing chosen yet (e.g. opened directly), show the first folder/device.
    let folder = if st.folder.is_empty() { s.folders.first().map(|f| f.id.clone()).unwrap_or_default() } else { st.folder.clone() };
    let device = if st.device.is_empty() { s.devices.first().map(|d| d.id.clone()).unwrap_or_default() } else { st.device.clone() };
    let st = Shared { snap: None, folder, device };
    match ui.get_page() {
        2 => match s.folders.iter().find(|f| f.id == st.folder) {
            Some(f) => ui.set_current_folder(folder_item(f, s)),
            None => ui.set_page(0),
        },
        3 => match s.devices.iter().find(|d| d.id == st.device) {
            Some(d) => ui.set_current_device(device_item(d, s)),
            None => ui.set_page(0),
        },
        _ => {
            if let Some(f) = s.folders.iter().find(|f| f.id == st.folder) { ui.set_current_folder(folder_item(f, s)); }
            if let Some(d) = s.devices.iter().find(|d| d.id == st.device) { ui.set_current_device(device_item(d, s)); }
        }
    }
}
