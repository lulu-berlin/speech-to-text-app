//! The service's settings (roadmap item 67): read and saved whole, the calls the browser's Settings
//! page makes, with the per-install secret (`docs/api/settings.json`), the language list its
//! `defaultLanguage` picks from (`docs/api/languages.json`), and the installed frontends its
//! `defaultFrontend` picks from (`docs/api/frontends.json`, roadmap item 66).

use std::collections::BTreeMap;
use std::io;

use serde::{Deserialize, Serialize};

use crate::Service;
use crate::response_json;

/// One language the service accepts, as `docs/api/languages.json` lists it (item 14).
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Language {
    pub code: String,
    pub name: String,
}

/// The browser's own language list, compiled in like the UI text (ADR 0029: the contract is
/// mirrored by hand).
const LANGUAGES: &str = include_str!("../../../docs/api/languages.json");

/// Every language `defaultLanguage` accepts, `auto` first.
pub fn languages() -> io::Result<Vec<Language>> {
    #[derive(Deserialize)]
    struct Languages {
        languages: Vec<Language>,
    }
    serde_json::from_str::<Languages>(LANGUAGES)
        .map(|list| list.languages)
        .map_err(io::Error::other)
}

/// The settings as `GET /api/settings` answers them. Serialized, they are the body of a
/// `PUT /api/settings`: every stored setting, the default frontend and the terminal command
/// included - the contract reads a missing one as none - and not the read-only device list, GPU
/// names, probe error or CPU-fallback notice.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub device: String,
    pub threads: Option<u32>,
    pub default_language: String,
    pub speakers_mode: String,
    pub speakers_count: Option<u32>,
    pub max_speakers: u32,
    pub default_frontend: Option<String>,
    pub terminal_command: Option<String>,
    #[serde(default, skip_serializing)]
    pub available_devices: Vec<String>,
    #[serde(default, skip_serializing)]
    pub gpu_names: BTreeMap<String, String>,
}

/// The stored settings and the devices the user can choose.
pub async fn settings(service: &Service) -> io::Result<Settings> {
    let response = crate::client()
        .get(format!("{}/api/settings", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}

/// Stores `settings` as a whole; the service answers with the settings as they now are.
pub async fn save_settings(service: &Service, settings: &Settings) -> io::Result<Settings> {
    let response = crate::client()
        .put(format!("{}/api/settings", service.endpoint))
        .bearer_auth(&service.secret)
        .json(settings)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}

/// One installed frontend, as `GET /api/frontends` lists it (`docs/api/frontends.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Frontend {
    pub id: String,
    pub name: String,
    pub kind: String,
}

/// Every frontend installed on this machine, the ones `defaultFrontend` may pick.
pub async fn frontends(service: &Service) -> io::Result<Vec<Frontend>> {
    #[derive(Deserialize)]
    struct Frontends {
        frontends: Vec<Frontend>,
    }
    let response = crate::client()
        .get(format!("{}/api/frontends", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Frontends>(response)
        .await
        .map(|list| list.frontends)
}
