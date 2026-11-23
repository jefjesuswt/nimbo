// Contrato en la nube: port de `data/cloud/Captions.kt` del teléfono (AGENTS.md
// › Formato en la nube). No romper; los tests de abajo son los de
// CaptionsTest.kt para que ambos lados no diverjan.

pub const TAG: &str = "#nimbo/";
const LEGACY_TAG: &str = "#toolbox/";
const META: &str = "#tgmeta";
const COVER: &str = "#tgcover";
pub const GEN: &str = "#tggen";
const NAME: &str = "#tgname";
pub const KEEP: &str = "#tgkeep";

pub const COVER_NAMES: [&str; 18] = [
    "cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "folder.jpg", "folder.jpeg", "folder.png", "front.jpg",
    "front.jpeg", "front.png", "albumart.jpg", "albumart.png", "album.jpg", "album.png", "artwork.jpg", "artwork.png",
    "thumb.jpg", "thumb.png",
];

fn is_folder_tag(w: &str) -> bool {
    w.starts_with(TAG) || w.starts_with(LEGACY_TAG)
}

fn words(s: &str) -> impl Iterator<Item = &str> {
    s.split_whitespace()
}

pub fn normalize_path(p: &str) -> String {
    p.trim().replace('\\', "/").split('/').map(str::trim).filter(|s| !s.is_empty() && *s != ".").collect::<Vec<_>>().join("/")
}

pub fn parse_folder(caption: &str) -> String {
    match words(caption).find(|w| is_folder_tag(w)) {
        Some(t) => normalize_path(t.strip_prefix(TAG).or_else(|| t.strip_prefix(LEGACY_TAG)).unwrap_or(t)),
        None => String::new(),
    }
}

pub fn tag_folder(caption: &str, folder: &str) -> String {
    let clean = words(caption).filter(|w| !is_folder_tag(w)).collect::<Vec<_>>().join(" ");
    let f = normalize_path(folder);
    if f.is_empty() {
        clean
    } else if clean.is_empty() {
        format!("{TAG}{f}")
    } else {
        format!("{clean} {TAG}{f}")
    }
}

// `#tgmeta <seg>s "título" "artista"`: (inicio, fin, título, artista, seg).
fn meta_span(c: &str) -> Option<(usize, usize, String, String, i32)> {
    let mut from = 0;
    while let Some(i) = c[from..].find("#tgmeta ") {
        let start = from + i;
        let rest = &c[start + 8..];
        let digits = rest.chars().take_while(|ch| ch.is_ascii_digit()).count();
        let parsed = (|| {
            if digits == 0 {
                return None;
            }
            let dur: i32 = rest[..digits].parse().ok()?;
            let r = rest[digits..].strip_prefix("s \"")?;
            let t_end = r.find('"')?;
            let title = &r[..t_end];
            let r2 = r[t_end + 1..].strip_prefix(" \"")?;
            let a_end = r2.find('"')?;
            let artist = &r2[..a_end];
            let len = 8 + digits + 3 + t_end + 1 + 2 + a_end + 1;
            Some((start, start + len, title.to_string(), artist.to_string(), dur))
        })();
        if parsed.is_some() {
            return parsed;
        }
        from = start + 1;
    }
    None
}

pub fn parse_meta(caption: &str) -> (String, String, i32) {
    meta_span(caption).map(|(_, _, t, a, d)| (t, a, d)).unwrap_or_default()
}

fn clip(s: &str) -> String {
    s.replace('"', "'").chars().take(120).collect()
}

pub fn tag_meta(caption: &str, title: &str, artist: &str, dur: i32) -> String {
    let clean = match meta_span(caption) {
        Some((a, b, ..)) => format!("{}{}", &caption[..a], &caption[b..]).trim().to_string(),
        None => caption.trim().to_string(),
    };
    if dur <= 0 && title.is_empty() && artist.is_empty() {
        return clean;
    }
    let tag = format!("{META} {}s \"{}\" \"{}\"", dur.max(0), clip(title), clip(artist));
    if clean.is_empty() { tag } else { format!("{clean} {tag}") }
}

pub fn tag_cover_link(caption: &str) -> String {
    // Fuera `#tgcover …` hasta el siguiente ` #` o el final.
    let clean = match caption.find("#tgcover ") {
        Some(a) => {
            let rest = &caption[a..];
            let b = rest[1..].find(" #").map(|i| a + 1 + i).unwrap_or(caption.len());
            format!("{}{}", &caption[..a], &caption[b..]).trim().to_string()
        }
        None => caption.trim().to_string(),
    };
    if clean.is_empty() { format!("{COVER} *") } else { format!("{clean} {COVER} *") }
}

pub fn tag_cover_name(caption: &str, name: &str) -> String {
    let clean = match caption.find("#tgname \"") {
        Some(a) => match caption[a + 9..].find('"') {
            Some(i) => format!("{}{}", &caption[..a], &caption[a + 9 + i + 1..]).trim().to_string(),
            None => caption.trim().to_string(),
        },
        None => caption.trim().to_string(),
    };
    let n = clip(name);
    if n.is_empty() {
        return clean;
    }
    let tag = format!("{NAME} \"{n}\"");
    if clean.is_empty() { tag } else { format!("{clean} {tag}") }
}

pub fn is_keep_mark(text: &str) -> bool {
    words(text).any(|w| w == KEEP)
}

pub fn is_gen_cover(caption: &str) -> bool {
    words(caption).any(|w| w == GEN)
}

pub fn is_cover_name(name: &str) -> bool {
    COVER_NAMES.contains(&name.to_lowercase().as_str())
}

pub fn is_audio_name(name: &str) -> bool {
    let ext = name.rsplit_once('.').map(|(_, e)| e.to_lowercase()).unwrap_or_default();
    ["mp3", "m4a", "ogg", "opus", "flac", "wav", "aac"].contains(&ext.as_str())
}

pub fn keep_text(folder: &str) -> String {
    format!("{TAG}{} {KEEP}", normalize_path(folder))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folders() {
        assert_eq!("", parse_folder("sin tag"));
        assert_eq!("music/albums", parse_folder("mix #nimbo/music/albums/"));
        assert_eq!("a/b", parse_folder("#nimbo//a//b"));
        assert_eq!("music/albums", parse_folder("mix #toolbox/music/albums"));
        assert_eq!("mix #nimbo/a/b", tag_folder("mix #toolbox/old", "a/b"));
        assert_eq!("music", normalize_path(" music "));
        assert_eq!("#nimbo/a", tag_folder("", "a"));
        assert_eq!("mix #nimbo/a/b", tag_folder("mix #nimbo/old", "a/b"));
        assert_eq!("mix", tag_folder("mix #nimbo/old", ""));
    }

    #[test]
    fn caption_meta() {
        let c = tag_meta("#nimbo/music", "Night Drive", "Some Artist", 3723);
        assert_eq!("#nimbo/music #tgmeta 3723s \"Night Drive\" \"Some Artist\"", c);
        assert_eq!(("Night Drive".into(), "Some Artist".into(), 3723), parse_meta(&c));
        assert_eq!((String::new(), String::new(), 0), parse_meta("sin meta"));
        // Re-etiquetar no duplica.
        assert_eq!(c, tag_meta(&tag_meta(&c, "X", "Y", 1), "Night Drive", "Some Artist", 3723));
    }

    #[test]
    fn cover_link() {
        assert!(is_cover_name("folder.JPG"));
        assert!(is_cover_name("Cover.png"));
        assert!(!is_cover_name("song.mp3"));
        assert!(!is_cover_name("booklet-01.jpg"));
        let c = tag_cover_name(&tag_cover_link("#nimbo/music"), "cover.jpg");
        assert_eq!("#nimbo/music #tgcover * #tgname \"cover.jpg\"", c);
        // Retag de carpeta conserva cover y nombre.
        assert_eq!("#tgcover * #tgname \"cover.jpg\" #nimbo/x", tag_folder(&c, "x"));
    }

    #[test]
    fn keep() {
        assert_eq!("#nimbo/a/b #tgkeep", keep_text("a/b/"));
        assert!(is_keep_mark(&keep_text("a")));
        assert_eq!("a", parse_folder(&keep_text("a")));
    }
}
