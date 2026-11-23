// TDLib en su propio hilo con tokio. La UI (GPUI) solo recibe eventos por
// un canal y manda órdenes por otro: ningún await de TDLib en el hilo de UI.
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, Ordering};
use tdlib_rs::enums::{self, AuthorizationState, MessageContent, Update};
use tdlib_rs::functions;
use tdlib_rs::types;
use crate::captions as cap;

// Saved Messages: se fija al listar y lo usan las subidas.
static CHAT: AtomicI64 = AtomicI64::new(0);
// Cliente de TDLib vivo. Cerrar sesión lo cierra; se crea otro en su lugar.
static CLIENT: AtomicI32 = AtomicI32::new(0);

// El llavero (zbus sobre tokio) bloquea: fuera de los hilos del runtime.
async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    tokio::task::spawn_blocking(f).await.unwrap()
}

pub fn client() -> i32 {
    CLIENT.load(Ordering::Relaxed)
}

fn chat() -> Option<i64> {
    let c = CHAT.load(Ordering::Relaxed);
    (c != 0).then_some(c)
}

// Tope de la caché de TDLib en bytes (0 = sin límite). Mismo criterio que
// StoragePolicy del teléfono: tope, 7 días sin usar fuera, 1 h de inmunidad.
pub static CACHE_CAP: AtomicI64 = AtomicI64::new(1 << 30);
const CACHE_TTL_SEC: i32 = 7 * 24 * 3600;
const IMMUNITY_SEC: i32 = 3600;
const TRIM_EVERY: std::time::Duration = std::time::Duration::from_secs(30 * 60);

#[derive(Clone, Debug)]
pub enum Auth {
    Loading,
    WaitPhone,
    WaitQr(String),
    WaitCode,
    WaitPassword(String),
    // Primer arranque: faltan api_id/api_hash (bool: hay de nimbo-cli).
    NeedKeys(bool),
    Ready,
}

#[derive(Clone, Debug)]
pub struct Item {
    pub msg_id: i64,
    pub caption: String,
    pub file_id: i32,
    pub name: String,
    pub folder: String,
    pub size: i64,
    pub kind: &'static str,
    pub thumb: i32,
    pub date: i32,
    pub title: String,
    pub artist: String,
}

impl Item {
    // Marcador de carpeta vacía (`#tgkeep`) o cover generada (`#tggen`):
    // cuentan para carpetas y operaciones, pero no se listan.
    pub fn hidden(&self) -> bool {
        self.kind == "keep" || self.kind == "cover"
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum UpState {
    Queued,
    Running(f32),
    Done,
    Failed,
}

#[derive(Clone, Debug)]
pub struct Upload {
    pub key: u64,
    pub down: bool,
    pub name: String,
    pub folder: String,
    pub state: UpState,
}

pub enum Event {
    Auth(Auth),
    Error(String),
    Progress(usize),
    Listed { items: Vec<Item>, seen: usize, ms: u128 },
    Me { name: String, phone: String },
    // Archivo local ya descargado (miniatura o imagen completa).
    Local(i32, PathBuf),
    Upload(Upload),
    // Sincronía en vivo: alta/cambio, baja, y temporal → definitivo.
    Upsert(Item),
    Removed(Vec<i64>),
    Rekey(i64, Item),
    Notice(String),
    Storage(i64),
    Cleared,
    LoggedOut,
}

pub enum Cmd {
    Qr,
    Phone(String),
    Code(String),
    Password(String),
    Keys(i32, String),
    ImportCliKeys,
    Fetch(i32),
    Upload { paths: Vec<PathBuf>, folder: String },
    // (archivo, carpeta relativa dentro de `dest`).
    Download { files: Vec<(Item, String)>, dest: PathBuf },
    SetCap(i64),
    // (mensaje, carpeta destino): retag del caption, cero bytes.
    Retag(Vec<(i64, String)>),
    SetTitle(i64, String),
    Delete(Vec<i64>),
    NewFolder(String),
    Storage,
    ClearCache,
    LogOut,
}

pub fn data_dir() -> PathBuf {
    let base = std::env::var("XDG_DATA_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(std::env::var("HOME").unwrap()).join(".local/share"));
    base.join("nimbo-desktop")
}

// Los spikes guardaban en nimbo-spike-gpui: se mueve una vez para conservar
// la sesión y las preferencias.
pub fn migrate_data_dir() {
    let new = data_dir();
    let old = new.with_file_name("nimbo-spike-gpui");
    if !new.exists() && old.exists() {
        let _ = std::fs::rename(&old, &new);
    }
}

fn thumb_of(t: &Option<types::Thumbnail>) -> i32 {
    t.as_ref().map(|t| t.file.id).unwrap_or(0)
}

pub fn item_of(m: &types::Message) -> Option<Item> {
    let (cap, file, name, mut kind, thumb) = match &m.content {
        MessageContent::MessageDocument(c) => {
            (&c.caption.text, &c.document.document, c.document.file_name.clone(), "doc", thumb_of(&c.document.thumbnail))
        }
        MessageContent::MessageAudio(c) => {
            (&c.caption.text, &c.audio.audio, c.audio.file_name.clone(), "audio", thumb_of(&c.audio.album_cover_thumbnail))
        }
        MessageContent::MessageVideo(c) => {
            let name = if c.video.file_name.is_empty() { format!("video-{}.mp4", m.id) } else { c.video.file_name.clone() };
            (&c.caption.text, &c.video.video, name, "video", thumb_of(&c.video.thumbnail))
        }
        MessageContent::MessagePhoto(c) => {
            let mut sizes: Vec<_> = c.photo.sizes.iter().collect();
            sizes.sort_by_key(|s| s.width * s.height);
            let full = sizes.last()?;
            let small = sizes.iter().find(|s| s.width >= 320).unwrap_or(full);
            let name = c.caption.text.split("#tgname \"").nth(1).and_then(|r| r.split('"').next())
                .map(str::to_string).unwrap_or_else(|| format!("photo-{}.jpg", m.id));
            (&c.caption.text, &full.photo, name, "photo", small.photo.id)
        }
        // Carpeta vacía: texto `#nimbo/<ruta> #tgkeep`.
        MessageContent::MessageText(c) if cap::is_keep_mark(&c.text.text) => {
            let folder = cap::parse_folder(&c.text.text);
            return Some(Item {
                msg_id: m.id, caption: c.text.text.clone(), file_id: 0, name: String::new(), folder, size: 0,
                kind: "keep", thumb: 0, date: m.date, title: String::new(), artist: String::new(),
            });
        }
        _ => return None,
    };
    // Cover generada (arte embebido): oculta, como en el teléfono.
    if cap::is_gen_cover(cap) {
        kind = "cover";
    }
    let (mut title, mut artist, _) = cap::parse_meta(cap);
    if let MessageContent::MessageAudio(c) = &m.content {
        if !c.audio.title.is_empty() {
            title = c.audio.title.clone();
        }
        if !c.audio.performer.is_empty() {
            artist = c.audio.performer.clone();
        }
    }
    Some(Item {
        msg_id: m.id, caption: cap.clone(), file_id: file.id, name, folder: cap::parse_folder(cap), size: file.size,
        kind, thumb, date: m.date, title, artist,
    })
}

pub struct Tg {
    pub served: Arc<AtomicU64>,
}

// Arranca TDLib y devuelve (eventos para la UI, órdenes desde la UI).
pub fn start(rt: &tokio::runtime::Runtime) -> (Tg, async_channel::Receiver<Event>, tokio::sync::mpsc::UnboundedSender<Cmd>) {
    CLIENT.store(tdlib_rs::create_client(), Ordering::Relaxed);
    let (ev_tx, ev_rx) = async_channel::unbounded();
    let (keys_tx, mut keys_rx) = tokio::sync::mpsc::unbounded_channel::<(i32, String)>();
    let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::unbounded_channel::<Cmd>();
    let (auth_tx, mut auth_rx) = tokio::sync::mpsc::unbounded_channel::<AuthorizationState>();

    // Lector de updates (bloqueante). Fuera de la autenticación, solo los
    // de Saved Messages: es la sincronía en vivo con el teléfono.
    let tx = ev_tx.clone();
    rt.spawn(async move {
        let mine = |c: i64| chat() == Some(c);
        loop {
            match tokio::task::spawn_blocking(tdlib_rs::receive).await.unwrap() {
                Some((Update::AuthorizationState(u), _)) => {
                    let _ = auth_tx.send(u.authorization_state);
                }
                Some((Update::NewMessage(u), _)) if mine(u.message.chat_id) => {
                    if let Some(it) = item_of(&u.message) {
                        let _ = tx.send(Event::Upsert(it)).await;
                    }
                }
                Some((Update::MessageSendSucceeded(u), _)) if mine(u.message.chat_id) => {
                    if let Some(it) = item_of(&u.message) {
                        let _ = tx.send(Event::Rekey(u.old_message_id, it)).await;
                    }
                }
                Some((Update::MessageContent(u), _)) if mine(u.chat_id) => {
                    // Caption cambiado (mover/renombrar desde el teléfono).
                    let tx = tx.clone();
                    tokio::spawn(async move {
                        match functions::get_message(u.chat_id, u.message_id, client()).await {
                            Ok(enums::Message::Message(m)) => match item_of(&m) {
                                Some(it) => { let _ = tx.send(Event::Upsert(it)).await; }
                                None => { let _ = tx.send(Event::Removed(vec![u.message_id])).await; }
                            },
                            Err(_) => {}
                        }
                    });
                }
                Some((Update::DeleteMessages(u), _)) if mine(u.chat_id) && u.is_permanent && !u.from_cache => {
                    let _ = tx.send(Event::Removed(u.message_ids)).await;
                }
                Some(_) => {}
                None => tokio::time::sleep(std::time::Duration::from_millis(10)).await,
            }
        }
    });

    // Subidas: una cola, de una en una (como el teléfono).
    let (up_tx, up_rx) = tokio::sync::mpsc::unbounded_channel::<(u64, Job)>();
    rt.spawn(transfers(up_rx, ev_tx.clone()));

    // Recorte periódico de la caché (y al cambiar el tope: SetCap).
    let (trim_tx, mut trim_rx) = tokio::sync::mpsc::unbounded_channel::<()>();
    rt.spawn(async move {
        loop {
            let cap = CACHE_CAP.load(Ordering::Relaxed);
            if cap > 0 {
                let _ = functions::optimize_storage(cap, CACHE_TTL_SEC, i32::MAX, IMMUNITY_SEC, vec![], vec![], vec![], false, 0, client()).await;
            }
            let _ = tokio::time::timeout(TRIM_EVERY, trim_rx.recv()).await;
        }
    });

    // Órdenes de la UI.
    let tx = ev_tx.clone();
    rt.spawn(async move {
        let mut next_key = 0u64;
        while let Some(c) = cmd_rx.recv().await {
            let client = client();
            let r = match c {
                Cmd::Keys(id, hash) => {
                    let h = hash.clone();
                    if !blocking(move || crate::secrets::set_keys(id, &h)).await {
                        let _ = tx.send(Event::Error("Couldn't save the keys in the system keyring".into())).await;
                    }
                    let _ = keys_tx.send((id, hash));
                    continue;
                }
                Cmd::ImportCliKeys => {
                    if let Some((id, hash)) = crate::secrets::cli_keys() {
                        let h = hash.clone();
                        let _ = blocking(move || crate::secrets::set_keys(id, &h)).await;
                        let _ = keys_tx.send((id, hash));
                    }
                    continue;
                }
                Cmd::Fetch(id) => {
                    let tx = tx.clone();
                    tokio::spawn(async move {
                        if let Ok(enums::File::File(f)) = functions::download_file(id, 8, 0, 0, true, client).await {
                            if f.local.is_downloading_completed {
                                let _ = tx.send(Event::Local(id, PathBuf::from(f.local.path))).await;
                            }
                        }
                    });
                    continue;
                }
                Cmd::Upload { paths, folder } => {
                    // Leer etiquetas/carátulas de una carpeta grande lleva un rato.
                    let jobs = tokio::task::spawn_blocking(move || expand(&paths, &folder)).await.unwrap_or_default();
                    for job in jobs {
                        next_key += 1;
                        let name = job.name.clone();
                        let _ = tx.send(Event::Upload(Upload { key: next_key, down: false, name, folder: job.folder.clone(), state: UpState::Queued })).await;
                        let _ = up_tx.send((next_key, Job::Up(job)));
                    }
                    continue;
                }
                Cmd::Download { files, dest } => {
                    for (item, rel) in files {
                        next_key += 1;
                        let to = if rel.is_empty() { dest.clone() } else { dest.join(&rel) };
                        let shown = to.to_string_lossy().to_string();
                        let _ = tx.send(Event::Upload(Upload { key: next_key, down: true, name: item.name.clone(), folder: shown, state: UpState::Queued })).await;
                        let _ = up_tx.send((next_key, Job::Down(item, to)));
                    }
                    continue;
                }
                Cmd::Retag(moves) => {
                    let tx = tx.clone();
                    tokio::spawn(async move {
                        let (mut ok, n) = (0, moves.len());
                        for (id, folder) in moves {
                            if edit_caption(client, id, |c| cap::tag_folder(c, &folder)).await {
                                ok += 1;
                            }
                            // Pausa anti-flood, como CloudOps.move del teléfono.
                            tokio::time::sleep(std::time::Duration::from_millis(400)).await;
                        }
                        let msg = if ok == n { format!("Moved {ok} item{}", if ok == 1 { "" } else { "s" }) } else { format!("Moved {ok} of {n}") };
                        let _ = tx.send(Event::Notice(msg)).await;
                    });
                    continue;
                }
                Cmd::SetTitle(id, title) => {
                    // Renombrar = título en #tgmeta; el archivo no se toca.
                    let ok = edit_caption(client, id, |c| {
                        let (_, artist, dur) = cap::parse_meta(c);
                        cap::tag_meta(c, &title, &artist, dur)
                    })
                    .await;
                    let _ = tx.send(Event::Notice(if ok { "Renamed".into() } else { "Couldn't rename".into() })).await;
                    continue;
                }
                Cmd::Delete(ids) => {
                    let Some(chat) = chat() else { continue };
                    let n = ids.len();
                    let mut ok = true;
                    for part in ids.chunks(100) {
                        ok &= functions::delete_messages(chat, part.to_vec(), true, client).await.is_ok();
                    }
                    let msg = if ok { format!("Deleted {n} item{}", if n == 1 { "" } else { "s" }) } else { "Couldn't delete everything".into() };
                    let _ = tx.send(Event::Notice(msg)).await;
                    continue;
                }
                Cmd::NewFolder(path) => {
                    let Some(chat) = chat() else { continue };
                    let content = enums::InputMessageContent::InputMessageText(types::InputMessageText {
                        text: types::FormattedText { text: cap::keep_text(&path), entities: vec![] },
                        link_preview_options: None,
                        clear_draft: true,
                    });
                    if functions::send_message(chat, None, None, None, content, client).await.is_err() {
                        let _ = tx.send(Event::Notice("Couldn't create the folder".into())).await;
                    }
                    continue;
                }
                Cmd::SetCap(b) => {
                    CACHE_CAP.store(b, Ordering::Relaxed);
                    let _ = trim_tx.send(());
                    continue;
                }
                Cmd::Storage => {
                    if let Ok(enums::StorageStatisticsFast::StorageStatisticsFast(s)) = functions::get_storage_statistics_fast(client).await {
                        let _ = tx.send(Event::Storage(s.files_size + s.database_size + s.log_size)).await;
                    }
                    continue;
                }
                Cmd::ClearCache => {
                    // Mismos valores que "Liberar espacio" del teléfono: todo fuera.
                    let _ = functions::optimize_storage(0, 0, 0, 0, vec![], vec![], vec![], false, 0, client).await;
                    let _ = tx.send(Event::Cleared).await;
                    if let Ok(enums::StorageStatisticsFast::StorageStatisticsFast(s)) = functions::get_storage_statistics_fast(client).await {
                        let _ = tx.send(Event::Storage(s.files_size + s.database_size + s.log_size)).await;
                    }
                    continue;
                }
                // El estado Closed que sigue lo recoge el bucle de autenticación.
                Cmd::LogOut => functions::log_out(client).await,
                Cmd::Qr => functions::request_qr_code_authentication(vec![], client).await,
                Cmd::Phone(p) => functions::set_authentication_phone_number(p, None, client).await,
                Cmd::Code(c) => functions::check_authentication_code(c, client).await,
                Cmd::Password(p) => functions::check_authentication_password(p, client).await,
            };
            if let Err(e) = r {
                let _ = tx.send(Event::Error(e.message)).await;
            }
        }
    });

    // Autenticación y, al quedar lista, el listado.
    let tx = ev_tx.clone();
    rt.spawn(async move {
        let _ = functions::set_log_verbosity_level(1, client()).await;
        while let Some(s) = auth_rx.recv().await {
            let client = client();
            let a = match s {
                AuthorizationState::WaitTdlibParameters => {
                    // Claves del llavero; la primera vez, las pide la ventana.
                    let (id, hash) = match blocking(crate::secrets::keys).await {
                        Some(k) => k,
                        None => {
                            let cli = crate::secrets::cli_keys().is_some();
                            let _ = tx.send(Event::Auth(Auth::NeedKeys(cli))).await;
                            match keys_rx.recv().await {
                                Some(k) => k,
                                None => continue,
                            }
                        }
                    };
                    let _ = tx.send(Event::Auth(Auth::Loading)).await;
                    let dir = data_dir().join("tdlib");
                    std::fs::create_dir_all(&dir).unwrap();
                    let d = dir.to_string_lossy().to_string();
                    // Base cifrada con la clave del llavero (vacía solo en una
                    // base de antes del cifrado: se cifra al entrar, abajo).
                    let key = blocking(|| crate::secrets::get("db_key")).await.unwrap_or_default();
                    let r = functions::set_tdlib_parameters(
                        false, d.clone(), d, key, true, true, true, false,
                        id, hash, "en".into(), "Nimbo Desktop".into(), "Linux".into(), env!("CARGO_PKG_VERSION").into(), client,
                    )
                    .await;
                    if let Err(e) = r {
                        let _ = tx.send(Event::Error(e.message)).await;
                    }
                    Auth::Loading
                }
                AuthorizationState::LoggingOut => Auth::Loading,
                // Sesión cerrada: fuera el chat y un cliente nuevo, que vuelve
                // a pedir parámetros (y con ellos el login).
                AuthorizationState::Closed => {
                    CHAT.store(0, Ordering::Relaxed);
                    let _ = tx.send(Event::LoggedOut).await;
                    CLIENT.store(tdlib_rs::create_client(), Ordering::Relaxed);
                    let _ = functions::set_log_verbosity_level(1, crate::tg::client()).await;
                    Auth::Loading
                }
                AuthorizationState::WaitPhoneNumber => Auth::WaitPhone,
                AuthorizationState::WaitOtherDeviceConfirmation(x) => Auth::WaitQr(x.link),
                AuthorizationState::WaitCode(_) => Auth::WaitCode,
                AuthorizationState::WaitPassword(x) => Auth::WaitPassword(x.password_hint),
                AuthorizationState::Ready => {
                    let _ = tx.send(Event::Auth(Auth::Ready)).await;
                    encrypt_db(client).await;
                    list(client, &tx).await;
                    continue;
                }
                _ => continue,
            };
            let _ = tx.send(Event::Auth(a)).await;
        }
    });

    (Tg { served: Arc::new(AtomicU64::new(0)) }, ev_rx, cmd_tx)
}

// Base sin clave (spikes anteriores o sin llavero): se genera una, se guarda
// en el llavero y TDLib re-cifra. Si el llavero falla, se queda como está.
async fn encrypt_db(client: i32) {
    if blocking(|| crate::secrets::get("db_key")).await.is_some() {
        return;
    }
    let Some(key) = crate::secrets::new_db_key() else { return };
    let k = key.clone();
    if !blocking(move || crate::secrets::set("db_key", &k)).await {
        return;
    }
    if functions::set_database_encryption_key(key, client).await.is_err() {
        blocking(|| crate::secrets::delete("db_key")).await;
    }
}

async fn list(client: i32, tx: &async_channel::Sender<Event>) {
    let t = std::time::Instant::now();
    let enums::User::User(me) = functions::get_me(client).await.unwrap();
    let name = format!("{} {}", me.first_name, me.last_name).trim().to_string();
    let _ = tx.send(Event::Me { name, phone: format!("+{}", me.phone_number) }).await;
    let enums::Chat::Chat(chat) = functions::create_private_chat(me.id, false, client).await.unwrap();
    CHAT.store(chat.id, Ordering::Relaxed);
    let (mut from, mut seen, mut items) = (0i64, 0usize, Vec::new());
    loop {
        let Ok(enums::Messages::Messages(r)) = functions::get_chat_history(chat.id, from, 0, 100, false, client).await else { break };
        let msgs: Vec<_> = r.messages.into_iter().flatten().collect();
        if msgs.is_empty() {
            break;
        }
        seen += msgs.len();
        let _ = tx.send(Event::Progress(seen)).await;
        items.extend(msgs.iter().filter_map(item_of));
        from = msgs.last().unwrap().id;
    }
    items.sort_by(|a, b| (&a.folder, &a.name).cmp(&(&b.folder, &b.name)));
    println!("listed {} of {} messages in {} ms", items.len(), seen, t.elapsed().as_millis());
    let _ = tx.send(Event::Listed { items, seen, ms: t.elapsed().as_millis() }).await;
}

// Lee [pos, pos+want) de un archivo: pide la descarga desde pos y espera a
// que TDLib tenga bytes contiguos ahí (streaming, como TgStreamSource).
pub async fn read(client: i32, id: i32, pos: i64, want: i64) -> Option<Vec<u8>> {
    let _ = functions::download_file(id, 32, pos, 0, false, client).await;
    let start = std::time::Instant::now();
    let have = loop {
        let enums::FileDownloadedPrefixSize::FileDownloadedPrefixSize(p) =
            functions::get_file_downloaded_prefix_size(id, pos, client).await.ok()?;
        let enums::File::File(f) = functions::get_file(id, client).await.ok()?;
        if p.size >= want || (f.local.is_downloading_completed && p.size > 0) {
            break p.size.min(want);
        }
        if start.elapsed().as_secs() > 60 {
            return None;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    };
    let enums::Data::Data(d) = functions::read_file_part(id, pos, have, client).await.ok()?;
    Some(decode(&d.data))
}

pub async fn size(client: i32, id: i32) -> i64 {
    match functions::get_file(id, client).await {
        Ok(enums::File::File(f)) => if f.size > 0 { f.size } else { f.expected_size },
        Err(_) => 0,
    }
}

// Los bytes llegan en base64 por la interfaz JSON de TDLib.
fn decode(s: &str) -> Vec<u8> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(s).unwrap_or_default()
}

pub fn count(served: &AtomicU64, n: usize) {
    served.fetch_add(n as u64, Ordering::Relaxed);
}

// Qué se sube y con qué caption (TransferManager.UpJob del teléfono).
pub struct UpJob {
    pub path: PathBuf,
    pub name: String,
    pub folder: String,
    pub cover: Cover,
}

#[derive(Clone, Copy, PartialEq)]
pub enum Cover {
    No,
    // cover.jpg/folder.png… de la carpeta: `#tgcover * #tgname "x"`.
    File,
    // Arte embebido del primer audio: `#tgcover * #tggen` (oculta).
    Generated,
}

fn stage_dir() -> PathBuf {
    data_dir().join("stage")
}

// Archivos sueltos y carpetas (recursivas). Una carpeta soltada en `dir`
// queda como `dir/<carpeta>/…`. Carpeta con audios: su cover canónica viaja
// como cover; si no hay, el arte embebido del primer audio que lo traiga
// (walkDir del teléfono).
fn expand(paths: &[PathBuf], dir: &str) -> Vec<UpJob> {
    fn join(a: &str, b: &str) -> String {
        if a.is_empty() { b.to_string() } else { format!("{a}/{b}") }
    }
    fn hidden(p: &Path) -> bool {
        p.file_name().map(|n| n.to_string_lossy().starts_with('.')).unwrap_or(false)
    }
    fn name(p: &Path) -> String {
        p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()
    }
    fn file(p: &Path, folder: &str, cover: Cover) -> UpJob {
        UpJob { path: p.to_path_buf(), name: name(p), folder: folder.to_string(), cover }
    }
    fn walk(p: &Path, folder: &str, out: &mut Vec<UpJob>) {
        let sub = join(folder, &name(p));
        let mut kids: Vec<_> = std::fs::read_dir(p).into_iter().flatten().flatten().map(|e| e.path()).filter(|k| !hidden(k)).collect();
        kids.sort();
        let files: Vec<&PathBuf> = kids.iter().filter(|k| k.is_file()).collect();
        let audio = files.iter().any(|k| cap::is_audio_name(&name(k)));
        let cover = if audio { files.iter().find(|k| cap::is_cover_name(&name(k))).cloned() } else { None };
        for k in &kids {
            if k.is_dir() {
                walk(k, &sub, out);
            } else if k.is_file() {
                out.push(file(k, &sub, if Some(k) == cover { Cover::File } else { Cover::No }));
            }
        }
        if audio && cover.is_none() {
            if let Some(art) = files.iter().filter(|k| cap::is_audio_name(&name(k))).find_map(|k| embedded_cover(k)) {
                let d = stage_dir().join(format!("{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
                if std::fs::create_dir_all(&d).is_ok() && std::fs::write(d.join("cover.jpg"), art).is_ok() {
                    out.push(UpJob { path: d.join("cover.jpg"), name: "cover.jpg".into(), folder: sub.clone(), cover: Cover::Generated });
                }
            }
        }
    }
    let mut out = vec![];
    for p in paths {
        if hidden(p) {
            continue;
        }
        if p.is_dir() {
            walk(p, dir, &mut out);
        } else if p.is_file() {
            out.push(file(p, dir, Cover::No));
        }
    }
    out
}

// (título, artista, segundos) de las etiquetas del audio (audioMeta).
fn audio_meta(p: &Path) -> Option<(String, String, i32)> {
    use lofty::prelude::*;
    let f = lofty::read_from_path(p).ok()?;
    let dur = f.properties().duration().as_secs() as i32;
    let (t, a) = f
        .primary_tag()
        .or_else(|| f.first_tag())
        .map(|tag| (tag.title().map(|s| s.to_string()).unwrap_or_default(), tag.artist().map(|s| s.to_string()).unwrap_or_default()))
        .unwrap_or_default();
    if t.is_empty() && a.is_empty() && dur <= 0 { None } else { Some((t, a, dur)) }
}

// Arte embebido (hasta 5 MB, como embeddedCover del teléfono).
fn embedded_cover(p: &Path) -> Option<Vec<u8>> {
    use lofty::prelude::*;
    let f = lofty::read_from_path(p).ok()?;
    let tag = f.primary_tag().or_else(|| f.first_tag())?;
    let pic = tag.pictures().first()?;
    (1..=5 * 1024 * 1024).contains(&pic.data().len()).then(|| pic.data().to_vec())
}

// Caption actual del servidor → `f` → se guarda si cambió.
async fn edit_caption(client: i32, id: i64, f: impl FnOnce(&str) -> String) -> bool {
    let Some(chat) = chat() else { return false };
    let Ok(enums::Message::Message(m)) = functions::get_message(chat, id, client).await else { return false };
    let Some(it) = item_of(&m) else { return false };
    let new = f(&it.caption);
    if new == it.caption {
        return true;
    }
    let text = (!new.is_empty()).then(|| types::FormattedText { text: new, entities: vec![] });
    functions::edit_message_caption(chat, id, text, false, client).await.is_ok()
}

pub enum Job {
    Up(UpJob),
    Down(Item, PathBuf),
}

// Subidas y descargas en una sola cola, de una en una (como el teléfono).
async fn transfers(
    mut rx: tokio::sync::mpsc::UnboundedReceiver<(u64, Job)>,
    tx: async_channel::Sender<Event>,
) {
    while let Some((key, job)) = rx.recv().await {
        let client = client();
        let (down, name, folder) = match &job {
            Job::Up(j) => (false, j.name.clone(), j.folder.clone()),
            Job::Down(item, to) => (true, item.name.clone(), to.to_string_lossy().to_string()),
        };
        let up = |state| Event::Upload(Upload { key, down, name: name.clone(), folder: folder.clone(), state });
        let _ = tx.send(up(UpState::Running(0.))).await;
        let report = |f| {
            let _ = tx.try_send(up(UpState::Running(f)));
        };
        let ok = match job {
            Job::Up(j) => {
                let r = upload_one(client, &j, report).await;
                // La cover extraída era temporal (TDLib ya la subió o falló).
                if j.cover == Cover::Generated {
                    if let Some(d) = j.path.parent() {
                        let _ = std::fs::remove_dir_all(d);
                    }
                }
                match r {
                    Some(item) => {
                        let _ = tx.send(Event::Upsert(item)).await;
                        true
                    }
                    None => false,
                }
            }
            Job::Down(item, to) => download_one(client, &item, &to, report).await.is_some(),
        };
        let _ = tx.send(up(if ok { UpState::Done } else { UpState::Failed })).await;
    }
}

// Baja el archivo, lo copia a `dir` (sin pisar: "x (1).ext") y suelta la
// copia de TDLib si no estaba ya en caché (como downloadOne del teléfono).
async fn download_one(client: i32, item: &Item, dir: &Path, report: impl Fn(f32)) -> Option<PathBuf> {
    let enums::File::File(f) = functions::get_file(item.file_id, client).await.ok()?;
    let cached = f.local.is_downloading_completed;
    let _ = functions::download_file(item.file_id, 1, 0, 0, false, client).await;
    let (mut last, mut since) = (-1i64, std::time::Instant::now());
    let local = loop {
        let enums::File::File(f) = functions::get_file(item.file_id, client).await.ok()?;
        if f.local.is_downloading_completed {
            break f.local.path;
        }
        if f.local.downloaded_size != last {
            last = f.local.downloaded_size;
            since = std::time::Instant::now();
            if item.size > 0 {
                report(last as f32 / item.size as f32);
            }
        } else if since.elapsed().as_secs() > 180 {
            return None;
        }
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    };
    std::fs::create_dir_all(dir).ok()?;
    let (stem, ext) = match item.name.rsplit_once('.') {
        Some((a, b)) if !a.is_empty() => (a.to_string(), format!(".{b}")),
        _ => (item.name.clone(), String::new()),
    };
    let mut to = dir.join(&item.name);
    let mut n = 1;
    while to.exists() {
        to = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    std::fs::copy(&local, &to).ok()?;
    if !cached {
        let _ = functions::delete_file(item.file_id, client).await;
    }
    Some(to)
}

async fn upload_one(client: i32, job: &UpJob, report: impl Fn(f32)) -> Option<Item> {
    let chat = chat()?;
    let path = &job.path;
    let mut text = cap::tag_folder("", &job.folder);
    match job.cover {
        Cover::No => {
            // Los documentos no traen metadatos de Telegram: viajan en #tgmeta.
            if cap::is_audio_name(&job.name) {
                let p = path.clone();
                if let Ok(Some((t, a, d))) = tokio::task::spawn_blocking(move || audio_meta(&p)).await {
                    text = cap::tag_meta(&text, &t, &a, d);
                }
            }
        }
        Cover::File => text = cap::tag_cover_name(&cap::tag_cover_link(&text), &job.name),
        Cover::Generated => text = format!("{} {}", cap::tag_cover_link(&text), cap::GEN),
    }
    let caption = (!text.trim().is_empty()).then(|| types::FormattedText { text: text.trim().to_string(), entities: vec![] });
    let content = enums::InputMessageContent::InputMessageDocument(types::InputMessageDocument {
        document: enums::InputFile::Local(types::InputFileLocal { path: path.to_string_lossy().to_string() }),
        thumbnail: None,
        disable_content_type_detection: true,
        caption,
    });
    let enums::Message::Message(m) = functions::send_message(chat, None, None, None, content, client).await.ok()?;
    let item = item_of(&m)?;
    // Hasta que el archivo esté arriba; sin avance en 3 min = fallo.
    let (mut last, mut since) = (-1i64, std::time::Instant::now());
    loop {
        let enums::File::File(f) = functions::get_file(item.file_id, client).await.ok()?;
        if f.remote.is_uploading_completed {
            return Some(item);
        }
        if f.remote.uploaded_size != last {
            last = f.remote.uploaded_size;
            since = std::time::Instant::now();
            let tot = if f.size > 0 { f.size } else { f.expected_size };
            if tot > 0 {
                report(last as f32 / tot as f32);
            }
        } else if since.elapsed().as_secs() > 180 {
            return None;
        }
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    }
}
