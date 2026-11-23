// Visor de texto: .md con formato, el resto tal cual (monoespaciado).
// Detecta UTF-8, UTF-16 con BOM o Shift-JIS (habitual en .txt japoneses).
use crate::app::{App, icon};
use crate::library::ext;
use crate::tg::{Cmd, Item};
use gpui_kit::component::button::*;
use gpui_kit::component::text::TextView;
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;

const TEXT_EXTS: [&str; 14] = ["txt", "md", "markdown", "srt", "vtt", "ass", "log", "json", "csv", "ini", "nfo", "cue", "lrc", "xml"];
const MAX_BYTES: i64 = 2 << 20;
// Más líneas no caben con fluidez en un solo div.
const MAX_LINES: usize = 20_000;

pub fn is_text(f: &Item) -> bool {
    TEXT_EXTS.contains(&ext(&f.name).as_str()) && f.size <= MAX_BYTES
}

pub fn decode(bytes: &[u8]) -> String {
    if let Some(rest) = bytes.strip_prefix(&[0xFF, 0xFE]) {
        return encoding_rs::UTF_16LE.decode(rest).0.into_owned();
    }
    if let Some(rest) = bytes.strip_prefix(&[0xFE, 0xFF]) {
        return encoding_rs::UTF_16BE.decode(rest).0.into_owned();
    }
    let bytes = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(bytes);
    if let Ok(s) = std::str::from_utf8(bytes) {
        return s.to_string();
    }
    let (s, _, bad) = encoding_rs::SHIFT_JIS.decode(bytes);
    if !bad {
        return s.into_owned();
    }
    encoding_rs::WINDOWS_1252.decode(bytes).0.into_owned()
}

pub struct TextDoc {
    pub item: Item,
    pub body: Option<String>,
}

impl App {
    pub fn open_text(&mut self, f: Item, cx: &mut Context<Self>) {
        let body = self.local.get(&f.file_id).and_then(|p| std::fs::read(p).ok()).map(|b| decode(&b));
        if body.is_none() {
            let _ = self.cmd.send(Cmd::Fetch(f.file_id));
        }
        self.text = Some(TextDoc { item: f, body });
        cx.notify();
    }

    // Llega el archivo descargado: si es el que se está viendo, a leerlo.
    pub fn text_arrived(&mut self, id: i32) {
        if let Some(doc) = &mut self.text {
            if doc.item.file_id == id && doc.body.is_none() {
                doc.body = self.local.get(&id).and_then(|p| std::fs::read(p).ok()).map(|b| decode(&b));
            }
        }
    }

    pub fn text_view(&mut self, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        let doc = self.text.as_ref().unwrap();
        let f = doc.item.clone();
        let md = matches!(ext(&f.name).as_str(), "md" | "markdown");
        let body: AnyElement = match &doc.body {
            None => div().flex_1().flex().items_center().justify_center().text_color(t.muted_foreground).child("Loading…").into_any_element(),
            Some(text) if md => div()
                .flex_1()
                .min_h_0()
                .w_full()
                .max_w(px(820.))
                .mx_auto()
                .px_6()
                .child(TextView::markdown("md", text.clone()).selectable(true).scrollable(true))
                .into_any_element(),
            Some(text) => {
                let lines: Vec<String> = text.lines().take(MAX_LINES).map(|l| if l.is_empty() { " ".into() } else { l.replace('\t', "    ") }).collect();
                div()
                    .id("text-scroll")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .px_6()
                    .py_4()
                    .font_family(t.mono_font_family.clone())
                    .text_size(px(13.))
                    .children(lines.into_iter().map(|l| div().child(l)))
                    .into_any_element()
            }
        };
        div()
            .size_full()
            .v_flex()
            .bg(t.background)
            .child(
                div()
                    .h_flex()
                    .h(px(56.))
                    .flex_none()
                    .px_3()
                    .gap_2()
                    .items_center()
                    .border_b_1()
                    .border_color(t.border)
                    .child(Button::new("text-close").ghost().icon(icon("x")).tooltip("Close (Esc)")
                        .on_click(cx.listener(|this, _, _, cx| { this.text = None; cx.notify(); })))
                    .child(
                        div()
                            .v_flex()
                            .flex_1()
                            .min_w_0()
                            .child(div().whitespace_nowrap().text_ellipsis().font_weight(FontWeight::MEDIUM).child(f.name.clone()))
                            .when(!f.folder.is_empty(), |d| d.child(div().text_size(px(12.)).text_color(t.muted_foreground).child(f.folder.clone()))),
                    )
                    .child({
                        let f = f.clone();
                        Button::new("text-dl").ghost().icon(icon("download")).tooltip("Download")
                            .on_click(cx.listener(move |this, _, _, cx| this.download(vec![f.clone()], None, cx)))
                    }),
            )
            .child(body)
    }
}

#[cfg(test)]
mod tests {
    use super::decode;

    #[test]
    fn encodings() {
        assert_eq!("hola", decode(b"hola"));
        assert_eq!("hola", decode(b"\xEF\xBB\xBFhola"));
        // "テスト" en Shift-JIS y en UTF-16LE con BOM.
        assert_eq!("テスト", decode(&[0x83, 0x65, 0x83, 0x58, 0x83, 0x67]));
        assert_eq!("ok", decode(&[0xFF, 0xFE, b'o', 0, b'k', 0]));
    }
}
