//! The engine's REST API, and a plain snapshot of folders, devices and requests.

use serde_json::{Value, json};
use std::time::Duration;

pub struct Api {
    agent: ureq::Agent,
    base: String,
    key: String,
}

pub fn enc(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

impl Api {
    pub fn new(base: String, key: String) -> Self {
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(10)))
            .http_status_as_error(false)
            .build()
            .into();
        Api { agent, base, key }
    }

    fn check(mut r: ureq::http::Response<ureq::Body>) -> Result<String, String> {
        let status = r.status();
        let body = r.body_mut().read_to_string().unwrap_or_default();
        if status.is_success() { Ok(body) } else { Err(if body.trim().is_empty() { format!("HTTP {status}") } else { body.trim().to_string() }) }
    }

    pub fn health(&self) -> bool {
        self.agent.get(format!("{}/rest/noauth/health", self.base)).call().map(|r| r.status().is_success()).unwrap_or(false)
    }

    pub fn get(&self, path: &str) -> Result<Value, String> {
        let r = self.agent.get(format!("{}{path}", self.base)).header("X-API-Key", &self.key).call().map_err(|e| e.to_string())?;
        serde_json::from_str(&Self::check(r)?).map_err(|e| e.to_string())
    }

    pub fn post(&self, path: &str, body: Option<&Value>) -> Result<(), String> {
        let req = self.agent.post(format!("{}{path}", self.base)).header("X-API-Key", &self.key);
        let r = match body {
            Some(b) => req.header("Content-Type", "application/json").send(b.to_string()),
            None => req.send_empty(),
        }
        .map_err(|e| e.to_string())?;
        Self::check(r).map(|_| ())
    }

    pub fn put(&self, path: &str, body: &Value) -> Result<(), String> {
        let r = self
            .agent
            .put(format!("{}{path}", self.base))
            .header("X-API-Key", &self.key)
            .header("Content-Type", "application/json")
            .send(body.to_string())
            .map_err(|e| e.to_string())?;
        Self::check(r).map(|_| ())
    }

    pub fn delete(&self, path: &str) -> Result<(), String> {
        let r = self.agent.delete(format!("{}{path}", self.base)).header("X-API-Key", &self.key).call().map_err(|e| e.to_string())?;
        Self::check(r).map(|_| ())
    }

    // ── changes ──

    pub fn add_device(&self, raw_id: &str, name: &str) -> Result<(), String> {
        let check = self.get(&format!("/rest/svc/deviceid?id={}", enc(raw_id.trim())))?;
        let id = check["id"].as_str().ok_or("That code doesn't look like a device ID")?.to_string();
        let mut d = self.get("/rest/config/defaults/device")?;
        d["deviceID"] = json!(id);
        d["name"] = json!(name);
        d["addresses"] = json!(["dynamic"]);
        self.post("/rest/config/devices", Some(&d))
    }

    pub fn create_folder(&self, id: &str, label: &str, path: &str, devices: &[String]) -> Result<(), String> {
        std::fs::create_dir_all(path).map_err(|e| format!("Couldn't create {path}: {e}"))?;
        let mut f = self.get("/rest/config/defaults/folder")?;
        f["id"] = json!(id);
        f["label"] = json!(label);
        f["path"] = json!(path);
        f["type"] = json!("sendreceive");
        f["hashers"] = json!(1);
        f["devices"] = Value::Array(devices.iter().map(|d| json!({"deviceID": d, "introducedBy": "", "encryptionPassword": ""})).collect());
        self.post("/rest/config/folders", Some(&f))
    }

    pub fn edit_folder(&self, id: &str, change: impl FnOnce(&mut Value)) -> Result<(), String> {
        let path = format!("/rest/config/folders/{}", enc(id));
        let mut f = self.get(&path)?;
        change(&mut f);
        self.put(&path, &f)
    }

    pub fn rename_device(&self, id: &str, name: &str) -> Result<(), String> {
        let path = format!("/rest/config/devices/{}", enc(id));
        let mut d = self.get(&path)?;
        d["name"] = json!(name);
        self.put(&path, &d)
    }

    /// Light settings measured to cut the engine's memory by ~40% without extra CPU.
    /// (Process-level settings live in the service file; these are the config-level ones.)
    pub fn apply_light_settings(&self) -> Result<(), String> {
        let mut o = self.get("/rest/config/options")?;
        if o["maxFolderConcurrency"] != json!(1) || o["progressUpdateIntervalS"] != json!(-1) {
            o["maxFolderConcurrency"] = json!(1);
            o["progressUpdateIntervalS"] = json!(-1);
            self.put("/rest/config/options", &o)?;
        }
        let folders = self.get("/rest/config/folders")?;
        for f in folders.as_array().into_iter().flatten() {
            if f["hashers"].as_i64().unwrap_or(0) == 0 {
                if let Some(id) = f["id"].as_str() {
                    self.edit_folder(id, |f| f["hashers"] = json!(1))?;
                }
            }
        }
        Ok(())
    }

    // ── reading ──

    pub fn snapshot(&self) -> Result<Snapshot, String> {
        let my_id = self.get("/rest/system/status")?["myID"].as_str().unwrap_or_default().to_string();
        let cfg = self.get("/rest/config")?;
        let conns = self.get("/rest/system/connections")?;
        let stats = self.get("/rest/stats/device").unwrap_or(json!({}));
        let pending_dev = self.get("/rest/cluster/pending/devices").unwrap_or(json!({}));
        let pending_fol = self.get("/rest/cluster/pending/folders").unwrap_or(json!({}));
        let version = self.get("/rest/system/version").ok().and_then(|v| v["version"].as_str().map(String::from)).unwrap_or_default();

        let name_of = |id: &str| -> String {
            cfg["devices"].as_array().into_iter().flatten()
                .find(|d| d["deviceID"] == json!(id))
                .and_then(|d| d["name"].as_str()).filter(|n| !n.is_empty()).map(String::from)
                .unwrap_or_else(|| id.chars().take(7).collect())
        };

        let mut devices = Vec::new();
        for d in cfg["devices"].as_array().into_iter().flatten() {
            let id = d["deviceID"].as_str().unwrap_or_default().to_string();
            if id == my_id { continue; }
            let c = &conns["connections"][&id];
            let connected = c["connected"].as_bool().unwrap_or(false);
            let completion = if connected {
                self.get(&format!("/rest/db/completion?device={}", enc(&id))).ok().and_then(|v| v["completion"].as_f64())
            } else { None };
            devices.push(Device {
                name: name_of(&id),
                connected,
                paused: d["paused"].as_bool().unwrap_or(false),
                last_seen: stats[&id]["lastSeen"].as_str().and_then(parse_time),
                address: if connected { c["address"].as_str().unwrap_or_default().to_string() } else { String::new() },
                relay: c["type"].as_str().unwrap_or_default().contains("relay"),
                completion,
                id,
            });
        }
        devices.sort_by_key(|d| d.name.to_lowercase());

        let mut folders = Vec::new();
        for f in cfg["folders"].as_array().into_iter().flatten() {
            let id = f["id"].as_str().unwrap_or_default().to_string();
            let paused = f["paused"].as_bool().unwrap_or(false);
            let st = if paused { json!({}) } else { self.get(&format!("/rest/db/status?folder={}", enc(&id))).unwrap_or(json!({})) };
            let n = |k: &str| st[k].as_i64().unwrap_or(0);
            folders.push(Folder {
                label: f["label"].as_str().filter(|l| !l.is_empty()).unwrap_or(&id).to_string(),
                path: f["path"].as_str().unwrap_or_default().to_string(),
                paused,
                device_ids: f["devices"].as_array().into_iter().flatten()
                    .filter_map(|d| d["deviceID"].as_str()).filter(|d| *d != my_id).map(String::from).collect(),
                state: if paused { "paused".into() } else { st["state"].as_str().filter(|s| !s.is_empty()).unwrap_or("unknown").to_string() },
                need_files: n("needFiles"),
                need_bytes: n("needBytes"),
                global_files: n("globalFiles"),
                global_bytes: n("globalBytes"),
                in_sync_bytes: n("inSyncBytes"),
                error: st["error"].as_str().filter(|e| !e.is_empty()).map(String::from),
                pull_errors: n("pullErrors"),
                id,
            });
        }
        folders.sort_by_key(|f| f.label.to_lowercase());

        let pending_devices = pending_dev.as_object().into_iter().flatten()
            .map(|(id, v)| PendingDevice { id: id.clone(), name: v["name"].as_str().filter(|n| !n.is_empty()).unwrap_or("Unnamed device").to_string() })
            .collect();
        let mut pending_folders = Vec::new();
        for (fid, v) in pending_fol.as_object().into_iter().flatten() {
            if folders.iter().any(|f| &f.id == fid) { continue; }
            for (dev, o) in v["offeredBy"].as_object().into_iter().flatten() {
                pending_folders.push(PendingFolder {
                    id: fid.clone(),
                    label: o["label"].as_str().filter(|l| !l.is_empty()).unwrap_or(fid).to_string(),
                    device_id: dev.clone(),
                    device_name: name_of(dev),
                });
            }
        }

        Ok(Snapshot { my_name: name_of(&my_id), my_id, version, folders, devices, pending_devices, pending_folders })
    }
}

#[derive(Clone, Debug)]
pub struct Folder {
    pub id: String,
    pub label: String,
    pub path: String,
    pub paused: bool,
    pub device_ids: Vec<String>,
    pub state: String,
    pub need_files: i64,
    pub need_bytes: i64,
    pub global_files: i64,
    pub global_bytes: i64,
    pub in_sync_bytes: i64,
    pub error: Option<String>,
    pub pull_errors: i64,
}

impl Folder {
    pub fn syncing(&self) -> bool { self.state.starts_with("sync") && self.need_files > 0 }
    pub fn scanning(&self) -> bool { self.state.starts_with("scan") }
    pub fn problem(&self) -> bool { self.state == "error" || self.error.is_some() || self.pull_errors > 0 }
    pub fn progress(&self) -> f32 {
        if self.global_bytes > 0 { (self.in_sync_bytes as f32 / self.global_bytes as f32).clamp(0.0, 1.0) } else { 1.0 }
    }
}

#[derive(Clone, Debug)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub connected: bool,
    pub paused: bool,
    pub last_seen: Option<i64>,
    pub address: String,
    pub relay: bool,
    pub completion: Option<f64>,
}

#[derive(Clone, Debug)]
pub struct PendingDevice { pub id: String, pub name: String }

#[derive(Clone, Debug)]
pub struct PendingFolder { pub id: String, pub label: String, pub device_id: String, pub device_name: String }

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub my_id: String,
    pub my_name: String,
    pub version: String,
    pub folders: Vec<Folder>,
    pub devices: Vec<Device>,
    pub pending_devices: Vec<PendingDevice>,
    pub pending_folders: Vec<PendingFolder>,
}

/// "2026-11-13T03:21:00.123456+02:00" → unix seconds. None for Syncthing's "never" (1970).
pub fn parse_time(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    if b.len() < 19 { return None; }
    let num = |a: usize, z: usize| s.get(a..z)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, se) = (num(0, 4)?, num(5, 7)?, num(8, 10)?, num(11, 13)?, num(14, 16)?, num(17, 19)?);
    // days from civil (Howard Hinnant)
    let y2 = if mo <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (mo + if mo > 2 { -3 } else { 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    let mut t = days * 86400 + h * 3600 + mi * 60 + se;
    let rest = &s[19..];
    if let Some(pos) = rest.find(['+', '-']) {
        let tz = &rest[pos..];
        let sign = if tz.starts_with('-') { -1 } else { 1 };
        let hh: i64 = tz.get(1..3).and_then(|v| v.parse().ok()).unwrap_or(0);
        let mm: i64 = tz.get(4..6).and_then(|v| v.parse().ok()).unwrap_or(0);
        t -= sign * (hh * 3600 + mm * 60);
    }
    (t > 86400).then_some(t)
}
