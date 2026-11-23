// Estado de la ventana y esqueleto: barra lateral + contenido + reproductor.
use crate::player::Player;
use crate::prefs::{Prefs, ThemePref};
use crate::tg::{Auth, Cmd, Event, Item, UpState, Upload};
use gpui_kit::component::input::{InputEvent, InputState};
use gpui_kit::component::{Theme, ThemeMode, *};
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;
use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap, HashSet};
use crate::ops::{Modal, Target};
use std::path::PathBuf;
use std::rc::Rc;
use tokio::sync::mpsc::UnboundedSender;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Section {
    Files,
    Music,
    Photos,
    Videos,
    Settings,
}

impl Section {
    pub fn label(self) -> &'static str {
        match self {
            Section::Files => "Files",
            Section::Music => "Music",
            Section::Photos => "Photos",
            Section::Videos => "Videos",
            Section::Settings => "Settings",
        }
    }
    fn icon(self) -> &'static str {
        match self {
            Section::Files => "folder",
            Section::Music => "music",
            Section::Photos => "image",
            Section::Videos => "film",
            Section::Settings => "settings",
        }
    }
}

pub fn icon(name: &str) -> Icon {
    Icon::empty().path(format!("icons/{name}.svg"))
}

pub struct App {
    pub(crate) auth: Auth,
    pub(crate) error: Option<String>,
    seen: usize,
    pub(crate) items: Vec<Item>,
    listed_ms: Option<u128>,
    pub(crate) field: Entity<InputState>,
    pub(crate) secret: Entity<InputState>,
    pub(crate) search: Entity<InputState>,
    pub(crate) cmd: UnboundedSender<Cmd>,
    pub(crate) port: u16,
    pub(crate) use_phone: bool,
    pub(crate) dir: String,
    pub(crate) player: Player,
    pub(crate) section: Section,
    pub(crate) prefs: Prefs,
    font: (SharedString, Pixels),
    // Archivos ya en disco (miniaturas e imágenes), por file id.
    pub(crate) local: Rc<HashMap<i32, PathBuf>>,
    pub(crate) asked: Rc<RefCell<HashSet<i32>>>,
    pub(crate) uploads: Vec<Upload>,
    pub(crate) viewer: Option<(Vec<Item>, usize)>,
    pub(crate) me: Option<(String, String)>,
    pub(crate) storage: Option<i64>,
    pub(crate) modal: Option<Modal>,
    pub(crate) modal_input: Entity<InputState>,
    pub(crate) selected: BTreeSet<Target>,
    pub(crate) toast: Option<String>,
    pub(crate) toast_gen: u64,
    pub(crate) focus: FocusHandle,
    pub(crate) albums: bool,
    pub(crate) album: Option<String>,
    pub(crate) covers: Rc<crate::library::Covers>,
    pub(crate) text: Option<crate::textview::TextDoc>,
    pub(crate) mpris: Option<crate::mpris::Mpris>,
    // Parar el reproductor necesita la ventana: se hace en el próximo render.
    reset_player: bool,
}

impl App {
    pub fn new(
        events: async_channel::Receiver<Event>,
        cmd: UnboundedSender<Cmd>,
        port: u16,
        font: (SharedString, Pixels),
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        // Eventos de TDLib → estado de la vista.
        cx.spawn(async move |this, cx| {
            while let Ok(ev) = events.recv().await {
                let alive = this.update(cx, |s, cx| {
                    s.on_event(ev, cx);
                    cx.notify();
                });
                if alive.is_err() {
                    break;
                }
            }
        })
        .detach();
        let search = cx.new(|cx| InputState::new(window, cx).placeholder("Search"));
        cx.subscribe(&search, |_, _, ev: &InputEvent, cx| {
            if matches!(ev, InputEvent::Change) {
                cx.notify();
            }
        })
        .detach();
        let modal_input = cx.new(|cx| InputState::new(window, cx));
        cx.subscribe(&modal_input, |this: &mut Self, _, ev: &InputEvent, cx| match ev {
            InputEvent::PressEnter { .. } => this.submit_modal(cx),
            InputEvent::Change => cx.notify(),
            _ => {}
        })
        .detach();
        let prefs = Prefs::load();
        let _ = cmd.send(Cmd::SetCap(prefs.cache_bytes()));
        let mut me = Self {
            auth: Auth::Loading,
            error: None,
            seen: 0,
            items: vec![],
            listed_ms: None,
            field: cx.new(|cx| InputState::new(window, cx)),
            secret: cx.new(|cx| InputState::new(window, cx).masked(true)),
            search,
            cmd,
            port,
            use_phone: false,
            dir: String::new(),
            player: Player::new(prefs.volume, window, cx),
            section: Section::Files,
            prefs,
            font,
            local: Rc::default(),
            asked: Rc::default(),
            uploads: vec![],
            viewer: None,
            me: None,
            storage: None,
            modal: None,
            modal_input,
            selected: BTreeSet::new(),
            toast: None,
            toast_gen: 0,
            focus: cx.focus_handle(),
            albums: false,
            album: None,
            covers: Rc::default(),
            text: None,
            mpris: None,
            reset_player: false,
        };
        me.apply_theme(window, cx);
        me.mpris = crate::mpris::Mpris::start(window, cx);
        window.focus(&me.focus, cx);
        me
    }

    fn on_event(&mut self, ev: Event, cx: &mut Context<Self>) {
        match ev {
            Event::Auth(a) => self.auth = a,
            Event::Error(e) => self.error = Some(e),
            Event::Progress(n) => self.seen = n,
            Event::Listed { items, seen, ms } => {
                self.items = items;
                self.seen = seen;
                self.listed_ms = Some(ms);
                self.recover();
            }
            Event::Me { name, phone } => self.me = Some((name, phone)),
            Event::Local(id, path) => {
                Rc::make_mut(&mut self.local).insert(id, path);
                self.text_arrived(id);
            }
            Event::Upload(u) => match self.uploads.iter_mut().find(|x| x.key == u.key) {
                Some(x) => {
                    // Un progreso tardío no pisa el final.
                    if !matches!(x.state, UpState::Done | UpState::Failed) {
                        *x = u;
                    }
                }
                None => self.uploads.push(u),
            },
            // Sincronía en vivo (teléfono, otra sesión o nuestras operaciones).
            Event::Upsert(item) => {
                self.put(item);
                self.recover();
            }
            Event::Rekey(old, item) => {
                self.items.retain(|x| x.msg_id != old);
                self.put(item);
                self.recover();
            }
            Event::Removed(ids) => {
                self.items.retain(|x| !ids.contains(&x.msg_id));
                self.recover();
                self.selected.retain(|t| !matches!(t, Target::File(id) if ids.contains(id)));
            }
            Event::Notice(msg) => self.notice(msg, cx),
            Event::Storage(b) => self.storage = Some(b),
            Event::Cleared => {
                self.local = Rc::default();
                self.asked.borrow_mut().clear();
            }
            // Sesión cerrada (aquí o desde otro dispositivo): todo a cero,
            // como el teléfono (memoria, índice y posiciones), y al login.
            Event::LoggedOut => {
                self.items.clear();
                self.listed_ms = None;
                self.seen = 0;
                self.me = None;
                self.storage = None;
                self.dir.clear();
                self.album = None;
                self.selected.clear();
                self.uploads.clear();
                self.viewer = None;
                self.text = None;
                self.modal = None;
                self.local = Rc::default();
                self.asked.borrow_mut().clear();
                self.covers = Rc::default();
                self.section = Section::Files;
                self.use_phone = false;
                self.error = None;
                self.player.forget();
                self.reset_player = true;
            }
        }
    }

    fn recover(&mut self) {
        self.covers = Rc::new(crate::library::covers(&self.items));
    }

    // Alta o cambio de un elemento, en su sitio por carpeta y nombre.
    fn put(&mut self, item: Item) {
        self.items.retain(|x| x.msg_id != item.msg_id);
        let at = self.items.partition_point(|x| (&x.folder, &x.name) < (&item.folder, &item.name));
        self.items.insert(at, item);
    }

    pub(crate) fn send_field(&mut self, secret: bool, make: fn(String) -> Cmd, cx: &mut Context<Self>) {
        let st = if secret { &self.secret } else { &self.field };
        let v = st.read(cx).value().trim().to_string();
        if !v.is_empty() {
            let _ = self.cmd.send(make(v));
        }
    }

    // Tema elegido + la fuente del sistema (cambiar de tema la reinicia).
    pub(crate) fn apply_theme(&self, window: &mut Window, cx: &mut gpui_kit::App) {
        match self.prefs.theme {
            ThemePref::System => Theme::sync_system_appearance(Some(window), cx),
            ThemePref::Light => Theme::change(ThemeMode::Light, Some(window), cx),
            ThemePref::Dark => Theme::change(ThemeMode::Dark, Some(window), cx),
        }
        let th = Theme::global_mut(cx);
        th.font_family = self.font.0.clone();
        th.font_size = self.font.1;
        window.refresh();
    }

    // Miniatura ya en disco o, si no, la pide una vez.
    pub(crate) fn thumb_path(&self, id: i32) -> Option<PathBuf> {
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

    // Archivos sueltos a la carpeta de descargas; una carpeta, con su
    // estructura (`dir/sub/x` baja como `<descargas>/dir/sub/x`).
    pub(crate) fn download(&mut self, files: Vec<Item>, folder: Option<&str>, cx: &mut Context<Self>) {
        let files: Vec<(Item, String)> = match folder {
            None => files.into_iter().map(|f| (f, String::new())).collect(),
            Some(dir) => {
                let base = dir.rsplit_once('/').map(|(a, _)| a.len() + 1).unwrap_or(0);
                self.items
                    .iter()
                    .filter(|f| !f.hidden() && (f.folder == dir || f.folder.starts_with(&format!("{dir}/"))))
                    .map(|f| (f.clone(), f.folder[base..].to_string()))
                    .collect()
            }
        };
        if files.is_empty() {
            return;
        }
        let _ = self.cmd.send(Cmd::Download { files, dest: self.prefs.downloads.clone() });
        cx.notify();
    }

    pub(crate) fn go(&mut self, s: Section, cx: &mut Context<Self>) {
        self.section = s;
        if s == Section::Settings {
            let _ = self.cmd.send(Cmd::Storage);
        }
        cx.notify();
    }

    fn sidebar(&mut self, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        let nav = |s: Section, cx: &mut Context<Self>| {
            let on = self.section == s;
            div()
                .id(s.label())
                .h_flex()
                .h(px(36.))
                .px_3()
                .gap_3()
                .items_center()
                .rounded(px(8.))
                .cursor_pointer()
                .when(on, |d| d.bg(t.sidebar_accent).text_color(t.sidebar_accent_foreground).font_weight(FontWeight::MEDIUM))
                .when(!on, |d| d.text_color(t.sidebar_foreground).hover(|s| s.bg(t.sidebar_accent)))
                .child(icon(s.icon()).with_size(px(17.)))
                .child(s.label())
                .on_click(cx.listener(move |this, _, _, cx| this.go(s, cx)))
        };
        let shown = self.items.iter().filter(|i| !i.hidden());
        let (count, total) = shown.fold((0usize, 0i64), |(n, b), i| (n + 1, b + i.size));
        div()
            .v_flex()
            .w(px(210.))
            .flex_none()
            .h_full()
            .p_3()
            .gap_1()
            .bg(t.sidebar)
            .border_r_1()
            .border_color(t.sidebar_border)
            .child(
                div()
                    .h_flex()
                    .gap_2()
                    .px_2()
                    .pb_4()
                    .pt_1()
                    .items_center()
                    .child(
                        div().size(px(30.)).rounded_full().bg(t.foreground).flex().items_center().justify_center()
                            .child(icon("cloud").with_size(px(16.)).text_color(t.background)),
                    )
                    .child(div().text_size(px(17.)).font_weight(FontWeight::SEMIBOLD).child("Nimbo")),
            )
            .child(nav(Section::Files, cx))
            .child(nav(Section::Music, cx))
            .child(nav(Section::Photos, cx))
            .child(nav(Section::Videos, cx))
            .child(div().flex_1())
            .child(nav(Section::Settings, cx))
            .child(
                div().px_3().pt_2().text_size(px(12.)).text_color(t.muted_foreground)
                    .child(format!("{count} files · {}", crate::library::fmt_size(total))),
            )
    }
}

impl Render for App {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        let root = div().size_full().relative().bg(t.background).text_color(t.foreground);
        let root = self.keyed(root, cx);
        if std::mem::take(&mut self.reset_player) {
            self.player.stop(window, cx);
        }
        if !matches!(self.auth, Auth::Ready) {
            return root.child(self.login(cx));
        }
        if self.text.is_some() {
            return root.child(self.text_view(cx));
        }
        if self.viewer.is_some() {
            return root.child(self.viewer_view(cx));
        }
        if self.player.video_open {
            return root.child(self.video_view(window, cx));
        }
        if self.player.now_open {
            return root.child(self.now_view(window, cx));
        }
        let loading = self.listed_ms.is_none();
        let seen = self.seen;
        let playing = self.player.current().is_some();
        let section = self.section;
        let body = if section == Section::Settings {
            self.settings(cx).into_any_element()
        } else if loading {
            div().flex_1().flex().items_center().justify_center().text_color(t.muted_foreground)
                .child(format!("Reading Saved Messages… {seen}")).into_any_element()
        } else {
            self.content(window, cx).into_any_element()
        };
        let uploads = !self.uploads.is_empty();
        root.child(
            div()
                .h_flex()
                .size_full()
                .child(self.sidebar(cx))
                .child(
                    div()
                        .id("main")
                        .v_flex()
                        .flex_1()
                        .h_full()
                        .overflow_hidden()
                        .relative()
                        .when(section != Section::Settings && self.selected.is_empty(), |d| d.child(self.header(cx)))
                        .when(section != Section::Settings && !self.selected.is_empty(), |d| d.child(self.selection_bar(cx)))
                        .child(div().flex_1().min_h_0().v_flex().child(body))
                        .when(playing, |d| d.child(self.player_bar(window.viewport_size().width - px(210.), cx)))
                        .when(uploads, |d| d.child(self.upload_panel(cx)))
                        // Soltar archivos o carpetas = subir a la carpeta actual.
                        .drag_over::<ExternalPaths>(move |s, _, _, _| s.bg(t.accent))
                        .on_drop(cx.listener(|this, paths: &ExternalPaths, _, cx| {
                            this.upload(paths.paths().to_vec(), cx);
                        })),
                ),
        )
        .when_some(self.toast_view(cx), |d, t| d.child(t))
        .when_some(self.modal_view(cx), |d, m| d.child(m))
    }
}
