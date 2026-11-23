// Secretos en el llavero del sistema (Secret Service: gnome-keyring,
// KWallet…), como SecretStore del teléfono: api_id/api_hash y la clave de la
// base de TDLib. Nunca en archivos ni en el repo.
use keyring::Entry;

const SERVICE: &str = "nimbo-desktop";

fn entry(name: &str) -> Option<Entry> {
    Entry::new(SERVICE, name).ok()
}

pub fn get(name: &str) -> Option<String> {
    entry(name)?.get_password().ok().filter(|s| !s.is_empty())
}

pub fn set(name: &str, value: &str) -> bool {
    entry(name).map(|e| e.set_password(value).is_ok()).unwrap_or(false)
}

pub fn delete(name: &str) {
    if let Some(e) = entry(name) {
        let _ = e.delete_credential();
    }
}

// Claves de la API de Telegram (my.telegram.org).
pub fn keys() -> Option<(i32, String)> {
    Some((get("api_id")?.parse().ok()?, get("api_hash")?))
}

pub fn set_keys(id: i32, hash: &str) -> bool {
    set("api_id", &id.to_string()) && set("api_hash", hash)
}

// Las de nimbo-cli (~/.config/nimbo/config), si están: para importarlas.
pub fn cli_keys() -> Option<(i32, String)> {
    let home = std::env::var("HOME").ok()?;
    let text = std::fs::read_to_string(format!("{home}/.config/nimbo/config")).ok()?;
    let get = |k: &str| {
        text.lines()
            .filter(|l| !l.trim_start().starts_with('#'))
            .find_map(|l| l.split_once('=').filter(|(a, _)| a.trim() == k).map(|(_, b)| b.trim().to_string()))
    };
    Some((get("api_id")?.parse().ok()?, get("api_hash")?))
}

// 32 bytes aleatorios en base64: la clave de la base de TDLib.
pub fn new_db_key() -> Option<String> {
    use base64::Engine;
    use std::io::Read;
    let mut b = [0u8; 32];
    std::fs::File::open("/dev/urandom").ok()?.read_exact(&mut b).ok()?;
    Some(base64::engine::general_purpose::STANDARD.encode(b))
}
