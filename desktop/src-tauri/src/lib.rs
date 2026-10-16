//! The desktop shell of Ting ID. The window runs the same wallet logic as the
//! mobile app; this side does what belongs to the operating system: it keeps
//! key secrets in the OS keychain, persists the app state as a JSON file in
//! the app data directory, and reaches the node through the HTTP plugin so no
//! CORS configuration is needed on the node.

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use tauri::{Manager, State};

/// The keychain service every secret is filed under; the key id is the account.
const SERVICE: &str = "Ting ID";

/// Non-secret device state (users, contacts, settings), one string per key.
/// Every change goes to a temporary file, is flushed to disk and renamed over
/// the state file; the version before it stays as `state.json.bak`.
struct Store {
    path: PathBuf,
    values: Mutex<BTreeMap<String, String>>,
    /// Why the saved state could not be read at start. While it stands nothing
    /// is read or written, until the person chooses to start fresh.
    problem: Mutex<Option<String>>,
}

/// The values saved at `path`; none when there is no file.
fn read_values(path: &Path) -> Result<Option<BTreeMap<String, String>>, String> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map(Some)
            .map_err(|e| format!("{} is damaged: {e}", path.display())),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(None),
        Err(e) => Err(format!("{} cannot be read: {e}", path.display())),
    }
}

/// Renames a file that could not be read out of the way, so nothing overwrites it.
fn set_aside(path: &Path, why: &str) {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let _ = fs::rename(path, path.with_extension(format!("json.{why}-{seconds}")));
}

impl Store {
    fn open(path: PathBuf) -> Self {
        let backup = path.with_extension("json.bak");
        let (values, problem) = match read_values(&path) {
            Ok(Some(values)) => (values, None),
            // No state file: a new installation, or one lost after its backup was written.
            Ok(None) => (
                read_values(&backup).ok().flatten().unwrap_or_default(),
                None,
            ),
            Err(problem) => match read_values(&backup) {
                Ok(Some(values)) => {
                    eprintln!("Ting ID: {problem}; using the backup");
                    set_aside(&path, "damaged");
                    (values, None)
                }
                _ => (BTreeMap::new(), Some(problem)),
            },
        };
        Self {
            path,
            values: Mutex::new(values),
            problem: Mutex::new(problem),
        }
    }

    fn problem(&self) -> Result<(), String> {
        match self
            .problem
            .lock()
            .map_err(|_| "state lock poisoned")?
            .as_ref()
        {
            Some(problem) => Err(problem.clone()),
            None => Ok(()),
        }
    }

    fn update(&self, change: impl FnOnce(&mut BTreeMap<String, String>)) -> Result<(), String> {
        self.problem()?;
        let mut values = self.values.lock().map_err(|_| "state lock poisoned")?;
        change(&mut values);
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        }
        let tmp = self.path.with_extension("json.tmp");
        let bytes = serde_json::to_vec(&*values).map_err(|e| e.to_string())?;
        let mut file = fs::File::create(&tmp).map_err(|e| e.to_string())?;
        file.write_all(&bytes).map_err(|e| e.to_string())?;
        file.sync_all().map_err(|e| e.to_string())?;
        drop(file);
        if self.path.exists() {
            fs::copy(&self.path, self.path.with_extension("json.bak"))
                .map_err(|e| e.to_string())?;
        }
        fs::rename(&tmp, &self.path).map_err(|e| e.to_string())
    }

    /// The person chose to start fresh: the unreadable file is set aside and the state starts empty.
    fn start_fresh(&self) -> Result<(), String> {
        let mut problem = self.problem.lock().map_err(|_| "state lock poisoned")?;
        if problem.take().is_some() {
            set_aside(&self.path, "unreadable");
        }
        Ok(())
    }
}

// The state commands run in order on the main thread: they are quick, and the
// last write of a key must be the one that stays. The keychain commands run
// off it, since a keychain can be slow or ask the person first.

#[tauri::command]
fn state_get(store: State<Store>, key: String) -> Result<Option<String>, String> {
    store.problem()?;
    let values = store.values.lock().map_err(|_| "state lock poisoned")?;
    Ok(values.get(&key).cloned())
}

#[tauri::command]
fn state_keys(store: State<Store>) -> Result<Vec<String>, String> {
    store.problem()?;
    let values = store.values.lock().map_err(|_| "state lock poisoned")?;
    Ok(values.keys().cloned().collect())
}

#[tauri::command]
fn state_start_fresh(store: State<Store>) -> Result<(), String> {
    store.start_fresh()
}

#[tauri::command]
fn state_set(store: State<Store>, key: String, value: String) -> Result<(), String> {
    store.update(|values| {
        values.insert(key, value);
    })
}

#[tauri::command]
fn state_remove(store: State<Store>, key: String) -> Result<(), String> {
    store.update(|values| {
        values.remove(&key);
    })
}

fn entry(service: &str, id: &str) -> Result<keyring_core::Entry, String> {
    // The keyring crate sets up the platform's keychain on first use.
    keyring::Entry::store_status()
        .as_ref()
        .map_err(|e| e.to_string())?;
    // On Windows a secret stays on this computer: the default persistence
    // copies credentials to every computer of a roaming profile.
    #[cfg(windows)]
    let modifiers = HashMap::from([("persistence", "Local")]);
    #[cfg(not(windows))]
    let modifiers = HashMap::new();
    keyring_core::Entry::new_with_modifiers(service, id, &modifiers).map_err(|e| e.to_string())
}

fn read(entry: &keyring_core::Entry) -> Result<Option<String>, String> {
    match entry.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

fn delete(entry: &keyring_core::Entry) -> Result<(), String> {
    match entry.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
async fn secret_set(id: String, value: String) -> Result<(), String> {
    entry(SERVICE, &id)?
        .set_password(&value)
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn secret_get(id: String) -> Result<Option<String>, String> {
    read(&entry(SERVICE, &id)?)
}

#[tauri::command]
async fn secret_delete(id: String) -> Result<(), String> {
    delete(&entry(SERVICE, &id)?)
}

/// What this machine is called in key names ("Anna · Windows PC").
#[tauri::command]
fn device_label() -> String {
    match std::env::consts::OS {
        "windows" => "Windows PC",
        "macos" => "Mac",
        "linux" => "Linux PC",
        other => other,
    }
    .to_string()
}

pub fn run() {
    let mut builder = tauri::Builder::default();
    // A `ting:` link opened while the app runs starts a second copy; this
    // hands the link to the running one, through the deep-link plugin, and
    // brings its window forward.
    #[cfg(desktop)]
    {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }));
    }
    builder
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .setup(|app| {
            let dir = app.path().app_data_dir()?;
            app.manage(Store::open(dir.join("state.json")));
            // Cookies the HTTP plugin kept before it had none.
            let _ = fs::remove_file(dir.join(".cookies"));
            // The installers register the `ting:` scheme. The app never writes
            // it itself on Windows: endpoint protection takes an unsigned
            // program that registers a URL handler at start for malware and
            // deletes it. An AppImage on Linux has no installer to do it.
            // Without the desktop's MIME tools this fails; the app still runs, only
            // `ting:` links do not open it.
            #[cfg(target_os = "linux")]
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                if let Err(e) = app.deep_link().register_all() {
                    eprintln!("Ting ID: ting: links are not registered: {e}");
                }
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            state_get,
            state_keys,
            state_start_fresh,
            state_set,
            state_remove,
            secret_set,
            secret_get,
            secret_delete,
            device_label
        ])
        .run(tauri::generate_context!())
        .expect("error while running Ting ID");
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("ting-id-{name}-{}", std::process::id()))
    }

    #[test]
    fn store_round_trips_through_the_file() {
        let dir = scratch("store");
        let path = dir.join("state.json");
        let store = Store::open(path.clone());
        store
            .update(|v| {
                v.insert("a".into(), "1".into());
            })
            .unwrap();
        store
            .update(|v| {
                v.insert("b".into(), "2".into());
                v.remove("a");
            })
            .unwrap();
        let again = Store::open(path);
        let values = again.values.lock().unwrap();
        assert_eq!(values.get("b").map(String::as_str), Some("2"));
        assert!(!values.contains_key("a"));
        drop(values);
        fs::remove_dir_all(dir).unwrap();
    }

    /// Touches the real OS keychain, so it runs on request: `cargo test -- --ignored`.
    #[test]
    #[ignore = "writes to and removes entries from the OS keychain"]
    fn secrets_round_trip_through_the_keychain() {
        let id = format!("test-{}", std::process::id());
        use tauri::async_runtime::block_on as run;
        assert_eq!(run(secret_get(id.clone())).unwrap(), None);
        run(secret_set(id.clone(), "0123abcd".into())).unwrap();
        assert_eq!(
            run(secret_get(id.clone())).unwrap().as_deref(),
            Some("0123abcd")
        );
        run(secret_delete(id.clone())).unwrap();
        assert_eq!(run(secret_get(id)).unwrap(), None);
    }

    /// A damaged state file is never overwritten: the backup stands in for it,
    /// and without one nothing is read or written until the person starts fresh.
    #[test]
    fn a_damaged_state_file_is_kept_and_reported() {
        let dir = scratch("damaged");
        let path = dir.join("state.json");
        let store = Store::open(path.clone());
        for (k, v) in [("a", "1"), ("b", "2")] {
            store
                .update(|values| {
                    values.insert(k.into(), v.into());
                })
                .unwrap();
        }
        drop(store);

        // The last write was lost half-way: the backup holds the state before it.
        fs::write(&path, b"").unwrap();
        let store = Store::open(path.clone());
        assert!(store.problem().is_ok());
        assert_eq!(
            store.values.lock().unwrap().get("a").map(String::as_str),
            Some("1")
        );
        assert!(
            !path.exists(),
            "the damaged file is set aside, not overwritten"
        );
        drop(store);

        // Both damaged: an error, and nothing is written over them.
        fs::write(&path, b"{not json").unwrap();
        fs::write(path.with_extension("json.bak"), b"{not json").unwrap();
        let store = Store::open(path.clone());
        assert!(store.problem().unwrap_err().contains("damaged"));
        assert!(store
            .update(|values| {
                values.insert("c".into(), "3".into());
            })
            .is_err());
        assert_eq!(fs::read(&path).unwrap(), b"{not json");

        // Starting fresh sets the unreadable file aside and writes again.
        store.start_fresh().unwrap();
        store
            .update(|values| {
                values.insert("c".into(), "3".into());
            })
            .unwrap();
        let kept = fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_name().to_string_lossy().contains("unreadable"))
            .count();
        assert_eq!(kept, 1);
        fs::remove_dir_all(dir).unwrap();
    }
}
