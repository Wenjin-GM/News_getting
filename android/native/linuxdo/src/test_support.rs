//! Offline fixtures only; never part of the APK library.
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
static NEXT: AtomicU64 = AtomicU64::new(0);
pub fn ech() -> Vec<u8> {
    let mut content = vec![1, 0, 32, 0, 32];
    content.extend([9].into_iter().chain([0; 31]));
    content.extend([0, 4, 0, 1, 0, 1, 0, 11]);
    content.extend(b"example.com");content.extend([0, 0]);
    let mut config = vec![0xfe, 0x0d];config.extend((content.len() as u16).to_be_bytes());config.extend(content);
    let mut list = (config.len() as u16).to_be_bytes().to_vec();list.extend(config);list
}
pub struct TempDir(pub PathBuf);
impl TempDir {
    pub fn new() -> Self {
        let parent = std::env::temp_dir().canonicalize().unwrap();
        let path = parent.join(format!("yilan-hardening-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&path).unwrap();Self(path)
    }
    pub fn str(&self) -> &str { self.0.to_str().unwrap() }
}
impl Drop for TempDir {
    fn drop(&mut self) {
        let parent = std::env::temp_dir().canonicalize().unwrap();
        if self.0.parent() == Some(parent.as_path())
            && self.0.file_name().unwrap().to_string_lossy().starts_with("yilan-hardening-") {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}
