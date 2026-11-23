mod app;
mod captions;
mod library;
mod mpv;
mod ops;
mod login;
mod player;
mod player_ui;
mod prefs;
mod settings;
mod stream;
mod tg;
mod uploads;
mod viewer;

use gpui_kit::component::Theme;
use gpui_kit::*;

// Fuente de la interfaz del sistema: la de GNOME/GTK (gsettings, "Google
// Sans 10") o, si no hay, la sans-serif de fontconfig. Tamaño en pt → px.
fn system_font() -> (String, f32) {
    let out = |cmd: &str, args: &[&str]| {
        std::process::Command::new(cmd).args(args).output().ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().trim_matches('\'').to_string())
            .filter(|s| !s.is_empty())
    };
    if let Some(v) = out("gsettings", &["get", "org.gnome.desktop.interface", "font-name"]) {
        if let Some((family, size)) = v.rsplit_once(' ') {
            if let Ok(pt) = size.parse::<f32>() {
                return (family.to_string(), pt * 4.0 / 3.0);
            }
        }
    }
    let family = out("fc-match", &["-f", "%{family[0]}", "sans-serif"]).unwrap_or_else(|| "sans-serif".into());
    (family, 14.0)
}

fn main() {
    // TDLib vive en su runtime tokio; GPUI tiene el hilo principal.
    let rt = Box::leak(Box::new(tokio::runtime::Runtime::new().unwrap()));
    let (tg, events, cmd) = tg::start(rt);
    let port = stream::serve(rt.handle().clone(), tg.client, tg.served.clone());
    let (family, font_px) = system_font();
    gpui_kit::application().with_assets(gpui_kit::assets::AllAssets).run(move |cx| {
        gpui_kit::init(cx);
        Theme::sync_system_appearance(None, cx);
        let font: (SharedString, Pixels) = (family.into(), px(font_px));
        let opts = WindowOptions {
            titlebar: Some(TitlebarOptions { title: Some("Nimbo".into()), ..Default::default() }),
            app_id: Some("nimbo".into()),
            window_bounds: Some(WindowBounds::Windowed(Bounds::centered(None, size(px(1100.), px(760.)), cx))),
            ..Default::default()
        };
        gpui_kit::open_window(opts, cx, move |window, cx| cx.new(|cx| app::App::new(events, cmd, port, font, window, cx)))
            .expect("window");
    });
}
