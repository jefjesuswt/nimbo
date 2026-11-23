// Gestionar archivos: nueva carpeta, renombrar, mover y borrar (como
// CloudOps del teléfono: todo es retag del caption salvo borrar). Menú por
// fila, selección múltiple (Ctrl+clic) y los diálogos.
use crate::app::{App, icon};
use crate::library::{Row, fmt_size};
use crate::tg::{Cmd, Item};
use gpui_kit::component::button::*;
use gpui_kit::component::input::Input;
use gpui_kit::component::menu::{PopupMenu, PopupMenuItem};
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;
use std::collections::BTreeSet;

#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Target {
    File(i64),
    Folder(String),
}

impl Target {
    pub fn of(row: &Row) -> Self {
        match row {
            Row::File(f) => Target::File(f.msg_id),
            Row::Folder { path, .. } => Target::Folder(path.clone()),
        }
    }
}

#[derive(Clone)]
pub enum Modal {
    NewFolder,
    Rename(Target),
    Move(Vec<Target>),
    Delete(Vec<Target>),
}

fn join(a: &str, b: &str) -> String {
    if a.is_empty() { b.to_string() } else if b.is_empty() { a.to_string() } else { format!("{a}/{b}") }
}

fn last(p: &str) -> &str {
    p.rsplit('/').next().unwrap_or(p)
}

fn parent(p: &str) -> &str {
    p.rsplit_once('/').map(|(a, _)| a).unwrap_or("")
}

fn under(folder: &str, dir: &str) -> bool {
    folder == dir || folder.starts_with(&format!("{dir}/"))
}

// Ruta de `folder` al llevar la carpeta `old` a `new` (subrutas intactas).
fn relocate(folder: &str, old: &str, new: &str) -> String {
    join(new, folder.strip_prefix(old).unwrap_or("").trim_start_matches('/'))
}

impl App {
    fn item(&self, id: i64) -> Option<&Item> {
        self.items.iter().find(|i| i.msg_id == id)
    }

    // Todas las carpetas que existen (con sus padres), para elegir destino.
    fn all_folders(&self) -> BTreeSet<String> {
        let mut out = BTreeSet::new();
        for i in &self.items {
            let mut p = i.folder.as_str();
            while !p.is_empty() {
                out.insert(p.to_string());
                p = parent(p);
            }
        }
        out
    }

    pub fn open_modal(&mut self, m: Modal, window: &mut Window, cx: &mut Context<Self>) {
        let text = match &m {
            Modal::NewFolder => String::new(),
            Modal::Rename(Target::File(id)) => self.item(*id).map(|f| if f.title.is_empty() { f.name.clone() } else { f.title.clone() }).unwrap_or_default(),
            Modal::Rename(Target::Folder(p)) => last(p).to_string(),
            Modal::Move(_) => self.dir.clone(),
            Modal::Delete(_) => String::new(),
        };
        self.modal_input.update(cx, |s, cx| {
            s.set_value(text, window, cx);
            s.focus(window, cx);
        });
        self.modal = Some(m);
        cx.notify();
    }

    pub fn close_modal(&mut self, cx: &mut Context<Self>) {
        self.modal = None;
        cx.notify();
    }

    // Lo que hace el botón principal del diálogo (o Enter).
    pub fn submit_modal(&mut self, cx: &mut Context<Self>) {
        let Some(m) = self.modal.clone() else { return };
        let text = self.modal_input.read(cx).value().trim().to_string();
        match m {
            Modal::NewFolder => {
                let name = crate::captions::normalize_path(&text);
                if name.is_empty() {
                    return;
                }
                let _ = self.cmd.send(Cmd::NewFolder(join(&self.dir, &name)));
            }
            Modal::Rename(Target::File(id)) => {
                if text.is_empty() {
                    return;
                }
                let _ = self.cmd.send(Cmd::SetTitle(id, text));
            }
            Modal::Rename(Target::Folder(p)) => {
                let name = text.replace('/', " ");
                if name.is_empty() || name == last(&p) {
                    return self.close_modal(cx);
                }
                self.move_folder(&p, &join(parent(&p), &name));
            }
            Modal::Move(targets) => {
                let dest = crate::captions::normalize_path(&text);
                let mut moves = vec![];
                for t in &targets {
                    match t {
                        Target::File(id) => moves.push((*id, dest.clone())),
                        Target::Folder(p) => {
                            if under(&dest, p) {
                                self.notice("A folder can't go inside itself".into(), cx);
                                return;
                            }
                            self.move_folder(p, &join(&dest, last(p)));
                        }
                    }
                }
                if !moves.is_empty() {
                    let _ = self.cmd.send(Cmd::Retag(moves));
                }
            }
            Modal::Delete(targets) => {
                let mut ids = vec![];
                for t in &targets {
                    match t {
                        Target::File(id) => ids.push(*id),
                        Target::Folder(p) => ids.extend(self.items.iter().filter(|i| under(&i.folder, p)).map(|i| i.msg_id)),
                    }
                }
                let _ = self.cmd.send(Cmd::Delete(ids));
            }
        }
        self.selected.clear();
        self.close_modal(cx);
    }

    // Carpeta entera a otra ruta: retag de archivos y covers; los
    // marcadores de vacía se borran y se recrean (renameFolder del teléfono).
    fn move_folder(&mut self, old: &str, new: &str) {
        let mut moves = vec![];
        let mut keeps = vec![];
        for i in self.items.iter().filter(|i| under(&i.folder, old)) {
            if i.kind == "keep" {
                keeps.push((i.msg_id, relocate(&i.folder, old, new)));
            } else {
                moves.push((i.msg_id, relocate(&i.folder, old, new)));
            }
        }
        // Carpeta sin nada dentro salvo su marcador: solo el marcador.
        if !keeps.is_empty() {
            let _ = self.cmd.send(Cmd::Delete(keeps.iter().map(|k| k.0).collect()));
            for (_, f) in keeps {
                let _ = self.cmd.send(Cmd::NewFolder(f));
            }
        }
        if !moves.is_empty() {
            let _ = self.cmd.send(Cmd::Retag(moves));
        }
        if under(&self.dir, old) {
            self.dir = relocate(&self.dir, old, new);
        }
    }

    pub fn notice(&mut self, msg: String, cx: &mut Context<Self>) {
        self.toast = Some(msg);
        self.toast_gen += 1;
        let g = self.toast_gen;
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(std::time::Duration::from_secs(4)).await;
            let _ = this.update(cx, |this, cx| {
                if this.toast_gen == g {
                    this.toast = None;
                    cx.notify();
                }
            });
        })
        .detach();
        cx.notify();
    }

    // Ctrl+clic: marca o desmarca la fila.
    pub fn toggle_select(&mut self, row: &Row, cx: &mut Context<Self>) {
        let t = Target::of(row);
        if !self.selected.remove(&t) {
            self.selected.insert(t);
        }
        cx.notify();
    }

    fn selection(&self) -> Vec<Target> {
        self.selected.iter().cloned().collect()
    }

    // Barra que sustituye a la cabecera mientras hay selección.
    pub fn selection_bar(&mut self, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        let n = self.selected.len();
        div()
            .h_flex()
            .h(px(56.))
            .flex_none()
            .px_3()
            .gap_2()
            .items_center()
            .border_b_1()
            .border_color(t.border)
            .bg(t.accent)
            .child(Button::new("sel-clear").ghost().icon(icon("x")).tooltip("Clear selection")
                .on_click(cx.listener(|this, _, _, cx| { this.selected.clear(); cx.notify(); })))
            .child(div().flex_1().font_weight(FontWeight::MEDIUM).child(format!("{n} selected")))
            .child(Button::new("sel-dl").ghost().icon(icon("download")).label("Download")
                .on_click(cx.listener(|this, _, _, cx| {
                    for t in this.selection() {
                        match t {
                            Target::File(id) => if let Some(f) = this.item(id).cloned() { this.download(vec![f], None, cx) },
                            Target::Folder(p) => this.download(vec![], Some(&p), cx),
                        }
                    }
                    this.selected.clear();
                })))
            .child(Button::new("sel-mv").ghost().icon(icon("folder-input")).label("Move")
                .on_click(cx.listener(|this, _, w, cx| { let s = this.selection(); this.open_modal(Modal::Move(s), w, cx) })))
            .child(Button::new("sel-rm").danger().icon(icon("trash")).label("Delete")
                .on_click(cx.listener(|this, _, w, cx| { let s = this.selection(); this.open_modal(Modal::Delete(s), w, cx) })))
    }

    // Diálogo encima de todo; clic fuera o Cancel lo cierra.
    pub fn modal_view(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let m = self.modal.clone()?;
        let t = cx.theme().clone();
        let (title, sub, action, danger, input) = match &m {
            Modal::NewFolder => ("New folder", format!("Inside {}", if self.dir.is_empty() { "Files" } else { &self.dir }), "Create", false, true),
            Modal::Rename(Target::File(_)) => ("Rename", "Changes the title shown in Nimbo; the file itself is untouched.".into(), "Rename", false, true),
            Modal::Rename(Target::Folder(_)) => ("Rename folder", "Everything inside moves with it.".into(), "Rename", false, true),
            Modal::Move(ts) => ("Move to…", format!("{} item{} · type a path or pick a folder", ts.len(), if ts.len() == 1 { "" } else { "s" }), "Move", false, true),
            Modal::Delete(ts) => {
                let (mut files, mut bytes) = (0usize, 0i64);
                for x in ts {
                    match x {
                        Target::File(id) => if let Some(f) = self.item(*id) { files += 1; bytes += f.size },
                        Target::Folder(p) => for f in self.items.iter().filter(|i| under(&i.folder, p) && !i.hidden()) { files += 1; bytes += f.size },
                    }
                }
                ("Delete from Telegram?", format!("{files} file{} · {}. This can't be undone.", if files == 1 { "" } else { "s" }, fmt_size(bytes)), "Delete", true, false)
            }
        };
        // Destinos que casan con lo escrito (diálogo de mover).
        let picks: Vec<String> = if let Modal::Move(_) = m {
            let q = self.modal_input.read(cx).value().to_lowercase();
            std::iter::once(String::new()).chain(self.all_folders()).filter(|f| f.to_lowercase().contains(q.trim())).take(8).collect()
        } else {
            vec![]
        };
        let card = div()
            .id("modal-card")
            .v_flex()
            .w(px(440.))
            .p_5()
            .gap_4()
            .rounded(px(14.))
            .border_1()
            .border_color(t.border)
            .bg(t.popover)
            .shadow_lg()
            .on_click(|_, _, cx| cx.stop_propagation())
            .child(
                div()
                    .v_flex()
                    .gap_1()
                    .child(div().text_size(px(17.)).font_weight(FontWeight::SEMIBOLD).child(title))
                    .child(div().text_size(px(13.)).text_color(t.muted_foreground).child(sub)),
            )
            .when(input, |d| d.child(Input::new(&self.modal_input)))
            .when(!picks.is_empty(), |d| {
                d.child(div().v_flex().max_h(px(240.)).children(picks.into_iter().enumerate().map(|(i, f)| {
                    let label = if f.is_empty() { "Files (top level)".to_string() } else { f.clone() };
                    div()
                        .id(("pick", i))
                        .h_flex()
                        .gap_2()
                        .px_2()
                        .py_1()
                        .rounded(px(6.))
                        .items_center()
                        .cursor_pointer()
                        .hover(|s| s.bg(t.secondary))
                        .child(icon("folder").with_size(px(14.)).text_color(t.muted_foreground))
                        .child(div().text_size(px(13.)).whitespace_nowrap().text_ellipsis().child(label))
                        .on_click(cx.listener(move |this, _, w, cx| {
                            let f = f.clone();
                            this.modal_input.update(cx, |s, cx| s.set_value(f, w, cx));
                            cx.notify();
                        }))
                })))
            })
            .child(
                div()
                    .h_flex()
                    .gap_2()
                    .justify_end()
                    .child(Button::new("m-cancel").ghost().label("Cancel").on_click(cx.listener(|this, _, _, cx| this.close_modal(cx))))
                    .child(Button::new("m-ok").label(action).map(|b| if danger { b.danger() } else { b.primary() })
                        .on_click(cx.listener(|this, _, _, cx| this.submit_modal(cx)))),
            );
        Some(
            div()
                .id("modal-scrim")
                .absolute()
                .inset_0()
                .flex()
                .items_center()
                .justify_center()
                .bg(hsla(0., 0., 0., 0.35))
                .on_click(cx.listener(|this, _, _, cx| this.close_modal(cx)))
                .child(card)
                .into_any_element(),
        )
    }

    pub fn toast_view(&self, cx: &gpui_kit::App) -> Option<Div> {
        let msg = self.toast.clone()?;
        let t = cx.theme();
        Some(
            div().absolute().bottom(px(110.)).left_0().right_0().flex().justify_center().child(
                div().px_4().py_2().rounded(px(999.)).bg(t.foreground).text_color(t.background).text_size(px(13.)).shadow_lg().child(msg),
            ),
        )
    }
}

// Menú de una fila (clic derecho o «⋯»).
pub fn row_menu(view: Entity<App>, row: Row, menu: PopupMenu) -> PopupMenu {
    let t = Target::of(&row);
    let file = matches!(row, Row::File(_));
    let v = view.clone();
    let r = row.clone();
    let mut menu = menu.item(PopupMenuItem::new("Download").icon(icon("download")).on_click(move |_, _, cx| {
        v.update(cx, |this, cx| match &r {
            Row::File(f) => this.download(vec![f.clone()], None, cx),
            Row::Folder { path, .. } => this.download(vec![], Some(path), cx),
        })
    }));
    for (label, ic, kind) in [("Rename", "pencil", 0), ("Move to…", "folder-input", 1), ("Delete", "trash", 2)] {
        let (v, t) = (view.clone(), t.clone());
        if kind == 2 {
            menu = menu.separator();
        }
        menu = menu.item(PopupMenuItem::new(if kind == 0 && file { "Rename title" } else { label }).icon(icon(ic)).on_click(move |_, w, cx| {
            let m = match kind {
                0 => Modal::Rename(t.clone()),
                1 => Modal::Move(vec![t.clone()]),
                _ => Modal::Delete(vec![t.clone()]),
            };
            v.update(cx, |this, cx| this.open_modal(m, w, cx))
        }));
    }
    menu
}
