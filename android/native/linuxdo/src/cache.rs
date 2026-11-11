//! Bounded, atomic, backwards-compatible cache in the app's private directory.
use super::dns::Resolved;
use super::*;
use base64::Engine;
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
const CACHE_FILE: &str = "linuxdo-ech-cache.json";
const CACHE_MAX_AGE_SECS: u64 = 7 * 24 * 60 * 60;
const CLOCK_SKEW_SECS: u64 = 300;
static NEXT: AtomicU64 = AtomicU64::new(0);
fn path(dir: &str) -> PathBuf { PathBuf::from(dir).join(CACHE_FILE) }

/// Call only after a successful site response. Cache hits never renew their age.
pub fn write(dir: Option<&str>, resolved: &Resolved) {
    let Some(dir) = dir else { return; };
    if !resolved.from_cache { let _ = write_at(dir, resolved, now_secs()); }
}
fn write_at(dir: &str, resolved: &Resolved, now: u64) -> Result<()> {
    let bytes = serde_json::to_vec(&serde_json::json!({"saved_at":now,
        "ech_b64":base64::engine::general_purpose::STANDARD.encode(&resolved.ech),
        "ips":resolved.ips.iter().map(Ipv4Addr::to_string).collect::<Vec<_>>(),
        "resolver":resolved.resolver}))?;
    ensure!(decode(&bytes, now).is_some(), "ECH cache update is invalid");
    let temp = PathBuf::from(dir).join(format!(".linuxdo-ech-cache-{}-{}.tmp",
        std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
    let mut options = std::fs::OpenOptions::new();options.write(true).create_new(true);
    #[cfg(unix)] {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temp)?;
    struct Cleanup(PathBuf);
    impl Drop for Cleanup { fn drop(&mut self) { let _ = std::fs::remove_file(&self.0); } }
    let _cleanup = Cleanup(temp.clone());
    let written = file.write_all(&bytes).and_then(|_| file.sync_all());drop(file);written?;
    std::fs::rename(temp, path(dir))?;Ok(())
}
pub fn read(dir: Option<&str>) -> Option<Resolved> {
    let mut bytes = Vec::new();
    std::fs::File::open(path(dir?)).ok()?.take(DNS_LIMIT as u64+1).read_to_end(&mut bytes).ok()?;
    decode(&bytes, now_secs())
}
fn decode(bytes: &[u8], now: u64) -> Option<Resolved> {
    if bytes.len() > DNS_LIMIT { return None; }
    let value: serde_json::Value = serde_json::from_slice(bytes).ok()?;
    let saved_at = value["saved_at"].as_u64()?;
    if saved_at > now.saturating_add(CLOCK_SKEW_SECS)
        || now.saturating_sub(saved_at) > CACHE_MAX_AGE_SECS { return None; }
    let ech = base64::engine::general_purpose::STANDARD.decode(value["ech_b64"].as_str()?).ok()?;
    EchConfig::new(EchConfigListBytes::from(ech.clone()),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES).ok()?;
    let mut ips = Vec::new();
    for ip in value["ips"].as_array()?.iter().filter_map(|v| v.as_str()?.parse::<Ipv4Addr>().ok()).filter(public_v4) {
        if ips.len() < 4 && !ips.contains(&ip) { ips.push(ip); }
    }
    if ips.is_empty() { return None; }
    let label: String = value["resolver"].as_str().unwrap_or("").chars().take(128).collect();
    Some(Resolved { ips, ech, resolver: format!("{label} · 本地缓存"), from_cache:true })
}
fn now_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d|d.as_secs()).unwrap_or(0)
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{ech,TempDir};
    fn resolved() -> Resolved {
        Resolved { ips:vec![Ipv4Addr::new(104,20,16,234)], ech:ech(), resolver:"test".into(), from_cache:false }
    }
    #[test]
    fn legacy_roundtrip_and_atomic_replacement_leave_no_temporary_files() {
        let dir=TempDir::new();let first=resolved();write(Some(dir.str()),&first);
        let back=read(Some(dir.str())).unwrap();
        assert_eq!(back.ips,first.ips);assert_eq!(back.ech,first.ech);assert!(back.from_cache);
        let mut second=resolved();second.ips=vec![Ipv4Addr::new(172,66,166,61)];write(Some(dir.str()),&second);
        assert_eq!(read(Some(dir.str())).unwrap().ips,second.ips);
        assert_eq!(std::fs::read_dir(&dir.0).unwrap().count(),1);
    }
    #[test]
    fn invalid_update_and_cache_hit_never_replace_or_renew_the_old_file() {
        let dir=TempDir::new();write(Some(dir.str()),&resolved());let before=std::fs::read(path(dir.str())).unwrap();
        let mut bad=resolved();bad.ech.clear();write(Some(dir.str()),&bad);
        write(Some(dir.str()),&read(Some(dir.str())).unwrap());
        assert_eq!(std::fs::read(path(dir.str())).unwrap(),before);
    }
    #[test]
    fn expiration_future_clock_corruption_and_oversize_are_bounded() {
        let dir=TempDir::new();let now=2_000_000;write_at(dir.str(),&resolved(),now).unwrap();
        let bytes=std::fs::read(path(dir.str())).unwrap();
        assert!(decode(&bytes,now+CACHE_MAX_AGE_SECS).is_some());
        assert!(decode(&bytes,now+CACHE_MAX_AGE_SECS+1).is_none());
        assert!(decode(&bytes,now-CLOCK_SKEW_SECS).is_some());
        assert!(decode(&bytes,now-CLOCK_SKEW_SECS-1).is_none());
        assert!(decode(b"{partial",now).is_none());
        std::fs::write(path(dir.str()),vec![b' ';DNS_LIMIT+1]).unwrap();assert!(read(Some(dir.str())).is_none());
    }
    #[test]
    fn absent_cache_and_private_addresses_are_not_usable() {
        let dir=TempDir::new();assert!(read(Some(dir.str())).is_none());assert!(read(None).is_none());write(None,&resolved());
        let mut bad=resolved();bad.ips=vec![Ipv4Addr::LOCALHOST];write(Some(dir.str()),&bad);
        assert!(read(Some(dir.str())).is_none());
    }
}
