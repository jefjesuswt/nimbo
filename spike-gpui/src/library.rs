// Biblioteca: carpetas virtuales (#nimbo/<ruta>) como en el teléfono, y las
// vistas Music/Photos/Videos sobre todo el árbol. Lista o cuadrícula.
use crate::app::{App, Section, icon};
use crate::ops::{Modal, Target, row_menu};
use gpui_kit::component::menu::{ContextMenuExt, DropdownMenu};
use crate::player::Track;
use crate::tg::{Cmd, Item};
use gpui_kit::component::button::*;
use gpui_kit::component::input::Input;
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::rc::Rc;

#[derive(Clone)]
pub enum Row {
    Folder { path: String, name: String, count: usize },
    File(Item),
}

pub fn fmt_size(b: i64) -> String {
    let b = b as f64;
    if b >= 1073741824. { format!("{:.1} GB", b / 1073741824.) }
    else if b >= 1048576. { format!("{:.1} MB", b / 1048576.) }
    else if b >= 1024. { format!("{:.0} KB", b / 1024.) }
    else { format!("{b} B") }
}

pub fn ext(name: &str) -> String {
    name.rsplit_once('.').map(|(_, e)| e.to_lowercase()).unwrap_or_default()
}

pub fn is_audio(f: &Item) -> bool {
    f.kind == "audio" || ["mp3", "flac", "m4a", "ogg", "opus", "wav", "aac"].contains(&ext(&f.name).as_str())
}

pub fn is_video(f: &Item) -> bool {
    f.kind == "video" || ["mp4", "mkv", "webm", "mov", "avi", "m4v"].contains(&ext(&f.name).as_str())
}

pub fn is_image(f: &Item) -> bool {
    f.kind == "photo" || ["jpg", "jpeg", "png", "gif", "webp"].contains(&ext(&f.name).as_str())
}

fn file_icon(f: &Item) -> &'static str {
    match ext(&f.name).as_str() {
        _ if is_audio(f) => "music",
        _ if is_video(f) => "film",
        _ if is_image(f) => "image",
        "txt" | "md" | "pdf" | "epub" | "srt" | "vtt" => "file-text",
        "zip" | "rar" | "7z" | "tar" | "gz" => "file-archive",
        _ => "file",
    }
}

fn matches(f: &Item, words: &[&str]) -> bool {
    let hay = format!("{} {}", f.name, f.folder).to_lowercase();
    words.iter().all(|w| hay.contains(w))
}

// Lo que se ve en `dir` (carpetas primero) o, buscando, todo lo que casa.
pub fn rows(items: &[Item], dir: &str, query: &str) -> Vec<Row> {
    let q = query.trim().to_lowercase();
    if !q.is_empty() {
        let words: Vec<&str> = q.split_whitespace().collect();
        return items.iter().filter(|f| !f.hidden() && matches(f, &words)).cloned().map(Row::File).collect();
    }
    let prefix = if dir.is_empty() { String::new() } else { format!("{dir}/") };
    let mut subs: BTreeMap<String, usize> = BTreeMap::new();
    let mut files = vec![];
    for f in items {
        if f.folder == dir {
            if !f.hidden() {
                files.push(Row::File(f.clone()));
            }
        } else if let Some(rest) = f.folder.strip_prefix(&prefix).filter(|_| !dir.is_empty() || !f.folder.is_empty()) {
            let rest = if dir.is_empty() { f.folder.as_str() } else { rest };
            let name = rest.split('/').next().unwrap_or(rest).to_string();
            // Marcadores y covers ocultas crean la carpeta pero no cuentan.
            *subs.entry(name).or_default() += usize::from(!f.hidden());
        }
    }
    let mut out: Vec<Row> = subs
        .into_iter()
        .map(|(name, count)| Row::Folder { path: format!("{prefix}{name}"), name, count })
        .collect();
    out.extend(files);
    out
}

// Music: por carpeta y nombre (álbumes juntos). Photos/Videos: lo último primero.
pub fn section_rows(items: &[Item], section: Section, dir: &str, query: &str) -> Vec<Row> {
    let keep: fn(&Item) -> bool = match section {
        Section::Files | Section::Settings => return rows(items, dir, query),
        Section::Music => is_audio,
        Section::Photos => is_image,
        Section::Videos => is_video,
    };
    let q = query.trim().to_lowercase();
    let words: Vec<&str> = q.split_whitespace().collect();
    let mut out: Vec<Item> = items.iter().filter(|f| !f.hidden() && keep(f) && matches(f, &words)).cloned().collect();
    if section != Section::Music {
        out.sort_by(|a, b| b.date.cmp(&a.date));
    }
    out.into_iter().map(Row::File).collect()
}

// Miniatura en disco o, si no está, la pide una vez (perezoso: solo las
// filas que GPUI pinta).
#[derive(Clone)]
struct Thumbs {
    local: Rc<HashMap<i32, PathBuf>>,
    asked: Rc<RefCell<HashSet<i32>>>,
    cmd: tokio::sync::mpsc::UnboundedSender<Cmd>,
}

impl Thumbs {
    fn get(&self, id: i32) -> Option<PathBuf> {
        if id == 0 {
            return None;
        }
        if let Some(p) = self.local.get(&id) {
            return Some(p.clone());
        }
        if self.asked.borrow_mut().insert(id) {
            let _ = self.cmd.send(Cmd::Fetch(id));
        }
        None
    }
}

fn thumb_id(row: &Row) -> i32 {
    match row {
        Row::File(f) => f.thumb,
        Row::Folder { .. } => 0,
    }
}

fn row_text(row: &Row, searching: bool, section: Section) -> (&'static str, String, String) {
    match row {
        Row::Folder { name, count, .. } => ("folder", name.clone(), format!("{count} items")),
        Row::File(f) => {
            let mut m = fmt_size(f.size);
            if (searching || section != Section::Files) && !f.folder.is_empty() {
                m = format!("{} · {m}", f.folder);
            }
            (file_icon(f), f.name.clone(), m)
        }
    }
}

impl App {
    pub fn header(&mut self, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        let files = self.section == Section::Files;
        let mut title = div().h_flex().items_center().gap_1().flex_1().overflow_hidden();
        if files {
            title = title.child(
                Button::new("root").ghost().label("Files").on_click(cx.listener(|this, _, _, cx| this.open_dir(String::new(), cx))),
            );
            let mut acc = String::new();
            for (i, seg) in self.dir.split('/').filter(|s| !s.is_empty()).enumerate() {
                acc = if acc.is_empty() { seg.to_string() } else { format!("{acc}/{seg}") };
                let path = acc.clone();
                title = title
                    .child(icon("chevron-right").with_size(px(14.)).text_color(t.muted_foreground))
                    .child(Button::new(("crumb", i)).ghost().label(seg.to_string()).on_click(cx.listener(move |this, _, _, cx| this.open_dir(path.clone(), cx))));
            }
        } else {
            title = title.child(div().px_2().text_size(px(17.)).font_weight(FontWeight::SEMIBOLD).child(self.section.label()));
        }
        let grid = self.prefs.grid;
        div()
            .h_flex()
            .h(px(56.))
            .flex_none()
            .px_3()
            .gap_2()
            .items_center()
            .border_b_1()
            .border_color(t.border)
            .when(files && !self.dir.is_empty(), |d| {
                d.child(Button::new("up").ghost().icon(icon("arrow-left")).tooltip("Back").on_click(cx.listener(|this, _, _, cx| {
                    let up = this.dir.rsplit_once('/').map(|(a, _)| a.to_string()).unwrap_or_default();
                    this.open_dir(up, cx)
                })))
            })
            .child(title)
            .child(div().w(px(220.)).child(Input::new(&self.search).prefix(icon("search")).cleanable(true)))
            .child(
                div()
                    .h_flex()
                    .child(Button::new("as-list").ghost().icon(icon("list")).tooltip("List").selected(!grid)
                        .on_click(cx.listener(|this, _, _, cx| this.set_grid(false, cx))))
                    .child(Button::new("as-grid").ghost().icon(icon("layout-grid")).tooltip("Grid").selected(grid)
                        .on_click(cx.listener(|this, _, _, cx| this.set_grid(true, cx)))),
            )
            .when(files && !self.dir.is_empty(), |d| {
                d.child(Button::new("dl-folder").ghost().icon(icon("download")).tooltip("Download this folder")
                    .on_click(cx.listener(|this, _, _, cx| { let dir = this.dir.clone(); this.download(vec![], Some(&dir), cx) })))
            })
            .when(files, |d| {
                d.child(Button::new("new-folder").ghost().icon(icon("folder-plus")).tooltip("New folder")
                    .on_click(cx.listener(|this, _, w, cx| this.open_modal(Modal::NewFolder, w, cx))))
            })
            .when(files, |d| {
                d.child(Button::new("up-folder").outline().icon(icon("folder-up")).tooltip("Upload a folder here")
                    .on_click(cx.listener(|this, _, _, cx| this.pick(true, cx))))
                    .child(Button::new("up-files").primary().icon(icon("upload")).label("Upload")
                        .on_click(cx.listener(|this, _, _, cx| this.pick(false, cx))))
            })
    }

    fn set_grid(&mut self, on: bool, cx: &mut Context<Self>) {
        self.prefs.grid = on;
        self.prefs.save();
        cx.notify();
    }

    pub fn content(&mut self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        let query = self.search.read(cx).value().to_string();
        let searching = !query.trim().is_empty();
        let section = self.section;
        let rows = Rc::new(section_rows(&self.items, section, &self.dir, &query));
        if rows.is_empty() {
            let msg = match (searching, section) {
                (true, _) => "No results",
                (_, Section::Files) => "This folder is empty. Drop files here or use Upload.",
                (_, Section::Music) => "No music yet",
                (_, Section::Photos) => "No photos yet",
                _ => "No videos yet",
            };
            return div().flex_1().flex().items_center().justify_center().text_color(cx.theme().muted_foreground)
                .child(msg).into_any_element();
        }
        let thumbs = Thumbs { local: self.local.clone(), asked: self.asked.clone(), cmd: self.cmd.clone() };
        let selected = Rc::new(self.selected.clone());
        let view = cx.entity();
        if !self.prefs.grid {
            return uniform_list("rows", rows.len(), move |range, _, cx| {
                let t = cx.theme().clone();
                range
                    .map(|i| {
                        let row = rows[i].clone();
                        let (ic, title, meta) = row_text(&row, searching, section);
                        let playable = matches!(&row, Row::File(f) if is_audio(f) || is_video(f));
                        let clickable = playable || matches!(&row, Row::Folder { .. }) || matches!(&row, Row::File(f) if is_image(f));
                        let thumb = thumbs.get(thumb_id(&row));
                        let (view, rows) = (view.clone(), rows.clone());
                        let picked = selected.contains(&Target::of(&row));
                        let (mv, mr) = (view.clone(), row.clone());
                        div()
                            .id(("row", i))
                            .w_full()
                            .when(picked, |d| d.bg(t.accent))
                            .h_flex()
                            .h(px(52.))
                            .px_4()
                            .gap_3()
                            .items_center()
                            .when(clickable, |d| d.cursor_pointer().hover(|s| s.bg(t.secondary)))
                            .child(
                                div()
                                    .size(px(36.))
                                    .flex_none()
                                    .rounded(px(8.))
                                    .overflow_hidden()
                                    .bg(if matches!(row, Row::Folder { .. }) { t.accent } else { t.secondary })
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .map(|d| match thumb {
                                        Some(p) => d.child(img(p).size_full().object_fit(ObjectFit::Cover)),
                                        None => d.child(icon(ic).with_size(px(18.)).text_color(t.muted_foreground)),
                                    }),
                            )
                            .child(
                                div()
                                    .v_flex()
                                    .flex_1()
                                    .overflow_hidden()
                                    .child(div().whitespace_nowrap().text_ellipsis().child(title))
                                    .child(div().text_size(px(12.)).whitespace_nowrap().text_ellipsis().text_color(t.muted_foreground).child(meta)),
                            )
                            .when(playable, |d| d.child(icon("play").with_size(px(16.)).text_color(t.muted_foreground)))
                            .child({
                                let (v, r) = (view.clone(), row.clone());
                                Button::new(("more", i)).ghost().small().icon(icon("ellipsis")).tooltip("More")
                                    .on_click(|_, _, cx| cx.stop_propagation())
                                    .dropdown_menu_with_anchor(Anchor::TopRight, move |m, _, _| row_menu(v.clone(), r.clone(), m))
                            })
                            .on_click(move |ev, _, cx| {
                                let ctrl = ev.modifiers().control || ev.modifiers().shift;
                                view.update(cx, |this, cx| if ctrl || !this.selected.is_empty() { this.toggle_select(&rows[i], cx) } else { this.activate(&rows, i, cx) })
                            })
                            .context_menu(move |m, _, _| row_menu(mv.clone(), mr.clone(), m))
                            .into_any_element()
                    })
                    .collect()
            })
            .flex_1()
            .into_any_element();
        }
        // Cuadrícula: filas de `cols` tarjetas según el ancho disponible.
        let width = f32::from(window.viewport_size().width) - 210. - 24.;
        let cols = ((width / 168.).floor() as usize).max(2);
        let lines = rows.len().div_ceil(cols);
        uniform_list("grid", lines, move |range, _, cx| {
            let t = cx.theme().clone();
            range
                .map(|line| {
                    let mut d = div().w_full().h_flex().h(px(196.)).px_3().gap_3().items_start();
                    for i in line * cols..(line * cols + cols) {
                        let Some(row) = rows.get(i).cloned() else {
                            d = d.child(div().flex_1());
                            continue;
                        };
                        let (ic, title, meta) = row_text(&row, searching, section);
                        let thumb = thumbs.get(thumb_id(&row));
                        let folder = matches!(row, Row::Folder { .. });
                        let (view, rows) = (view.clone(), rows.clone());
                        let picked = selected.contains(&Target::of(&row));
                        let (mv, mr) = (view.clone(), row.clone());
                        d = d.child(
                            div()
                                .id(("tile", i))
                                .when(picked, |d| d.bg(t.accent))
                                .v_flex()
                                .flex_1()
                                .min_w_0()
                                .p_2()
                                .gap_1()
                                .rounded(px(12.))
                                .cursor_pointer()
                                .hover(|s| s.bg(t.secondary))
                                .child(
                                    div()
                                        .w_full()
                                        .h(px(130.))
                                        .rounded(px(10.))
                                        .overflow_hidden()
                                        .bg(if folder { t.accent } else { t.secondary })
                                        .flex()
                                        .items_center()
                                        .justify_center()
                                        .map(|d| match thumb {
                                            Some(p) => d.child(img(p).size_full().object_fit(ObjectFit::Cover)),
                                            None => d.child(icon(ic).with_size(px(36.)).text_color(t.muted_foreground)),
                                        }),
                                )
                                .child(div().px_1().text_size(px(13.)).whitespace_nowrap().text_ellipsis().child(title))
                                .child(div().px_1().text_size(px(11.)).whitespace_nowrap().text_ellipsis().text_color(t.muted_foreground).child(meta))
                                .on_click(move |ev, _, cx| {
                                    let ctrl = ev.modifiers().control || ev.modifiers().shift;
                                    view.update(cx, |this, cx| if ctrl || !this.selected.is_empty() { this.toggle_select(&rows[i], cx) } else { this.activate(&rows, i, cx) })
                                })
                                .context_menu(move |m, _, _| row_menu(mv.clone(), mr.clone(), m)),
                        );
                    }
                    d
                })
                .collect()
        })
        .py_2()
        .flex_1()
        .into_any_element()
    }

    pub fn open_dir(&mut self, dir: String, cx: &mut Context<Self>) {
        self.dir = dir;
        cx.notify();
    }

    // Carpeta: entrar. Audio/vídeo: suena con la lista de alrededor como
    // cola. Imagen: visor con las demás imágenes de la vista.
    fn activate(&mut self, rows: &[Row], i: usize, cx: &mut Context<Self>) {
        match &rows[i] {
            Row::Folder { path, .. } => {
                if self.section != Section::Files {
                    self.section = Section::Files;
                }
                self.open_dir(path.clone(), cx)
            }
            Row::File(f) if is_audio(f) || is_video(f) => {
                let files: Vec<&Item> = rows.iter().filter_map(|r| match r {
                    Row::File(x) if is_audio(x) || is_video(x) => Some(x),
                    _ => None,
                }).collect();
                let at = files.iter().position(|x| x.file_id == f.file_id).unwrap_or(0);
                let queue = files.iter().map(|x| Track {
                    file_id: x.file_id,
                    url: format!("http://127.0.0.1:{}/f/{}", self.port, x.file_id),
                    title: if x.title.is_empty() { x.name.rsplit_once('.').map(|(a, _)| a).unwrap_or(&x.name).to_string() } else { x.title.clone() },
                    artist: x.artist.clone(),
                    folder: x.folder.clone(),
                    thumb: x.thumb,
                    video: is_video(x),
                }).collect();
                self.player.play_queue(queue, at);
                cx.notify();
            }
            Row::File(f) if is_image(f) => {
                let imgs: Vec<Item> = rows.iter().filter_map(|r| match r {
                    Row::File(x) if is_image(x) => Some(x.clone()),
                    _ => None,
                }).collect();
                let at = imgs.iter().position(|x| x.file_id == f.file_id).unwrap_or(0);
                self.open_viewer(imgs, at, cx);
            }
            Row::File(_) => {}
        }
    }

    // Selector del sistema (portal): archivos o una carpeta.
    fn pick(&mut self, dirs: bool, cx: &mut Context<Self>) {
        let rx = cx.prompt_for_paths(PathPromptOptions {
            files: !dirs,
            directories: dirs,
            multiple: true,
            prompt: Some("Upload".into()),
        });
        cx.spawn(async move |this, cx| {
            if let Ok(Ok(Some(paths))) = rx.await {
                let _ = this.update(cx, |this, cx| this.upload(paths, cx));
            }
        })
        .detach();
    }

    pub fn upload(&mut self, paths: Vec<PathBuf>, cx: &mut Context<Self>) {
        if paths.is_empty() {
            return;
        }
        let folder = if self.section == Section::Files { self.dir.clone() } else { String::new() };
        let _ = self.cmd.send(Cmd::Upload { paths, folder });
        cx.notify();
    }
}
