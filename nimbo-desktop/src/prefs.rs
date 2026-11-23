// Preferencias del escritorio: `clave=valor` en el directorio de datos.
use crate::tg::data_dir;
use std::path::PathBuf;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum ThemePref {
    System,
    Light,
    Dark,
}

// Topes de caché que ofrece Ajustes, en MB (0 = sin límite).
pub const CACHE_CHOICES: [(i64, &str); 4] = [(512, "512 MB"), (1024, "1 GB"), (5120, "5 GB"), (0, "No limit")];

#[derive(Clone, Debug)]
pub struct Prefs {
    pub theme: ThemePref,
    pub grid: bool,
    pub cache_mb: i64,
    pub downloads: PathBuf,
    pub volume: f64,
}

fn default_downloads() -> PathBuf {
    let home = PathBuf::from(std::env::var("HOME").unwrap_or_default());
    // Carpeta de descargas de XDG si existe; si no, ~/Downloads.
    let xdg = std::process::Command::new("xdg-user-dir").arg("DOWNLOAD").output().ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty() && std::path::Path::new(s) != home);
    xdg.map(PathBuf::from).unwrap_or_else(|| home.join("Downloads")).join("Nimbo")
}

impl Prefs {
    pub fn load() -> Self {
        let text = std::fs::read_to_string(data_dir().join("prefs")).unwrap_or_default();
        let get = |k: &str| text.lines().find_map(|l| l.split_once('=').filter(|(a, _)| a.trim() == k).map(|(_, b)| b.trim().to_string()));
        Self {
            theme: match get("theme").as_deref() {
                Some("light") => ThemePref::Light,
                Some("dark") => ThemePref::Dark,
                _ => ThemePref::System,
            },
            grid: get("view").as_deref() == Some("grid"),
            cache_mb: get("cache_mb").and_then(|v| v.parse().ok()).unwrap_or(1024),
            downloads: get("downloads").filter(|v| !v.is_empty()).map(PathBuf::from).unwrap_or_else(default_downloads),
            volume: get("volume").and_then(|v| v.parse().ok()).unwrap_or(100.),
        }
    }

    pub fn save(&self) {
        let theme = match self.theme {
            ThemePref::System => "system",
            ThemePref::Light => "light",
            ThemePref::Dark => "dark",
        };
        let view = if self.grid { "grid" } else { "list" };
        let _ = std::fs::create_dir_all(data_dir());
        let _ = std::fs::write(
            data_dir().join("prefs"),
            format!(
                "theme={theme}\nview={view}\ncache_mb={}\ndownloads={}\nvolume={:.0}\n",
                self.cache_mb,
                self.downloads.display(),
                self.volume
            ),
        );
    }

    pub fn cache_bytes(&self) -> i64 {
        self.cache_mb * 1024 * 1024
    }
}
