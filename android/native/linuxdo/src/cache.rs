//! Local ECH bootstrap cache. Only public DNS data is stored, in the app's
//! private directory. The cache is used solely when every live resolver fails.
use super::dns::Resolved;
use super::*;
use base64::Engine;
use std::path::PathBuf;

const CACHE_FILE: &str = "linuxdo-ech-cache.json";
const CACHE_MAX_AGE_SECS: u64 = 7 * 24 * 60 * 60;

fn path(cache_dir: &str) -> PathBuf {
    PathBuf::from(cache_dir).join(CACHE_FILE)
}

/// Persist a freshly resolved ECH set. Failures are non-fatal: the cache is a
/// resilience layer, never a requirement for the bounded request to succeed.
pub fn write(cache_dir: Option<&str>, resolved: &Resolved) {
    let Some(dir) = cache_dir else { return };
    let json = serde_json::json!({
        "saved_at": now_secs(),
        "ech_b64": base64::engine::general_purpose::STANDARD.encode(&resolved.ech),
        "ips": resolved.ips.iter().map(Ipv4Addr::to_string).collect::<Vec<_>>(),
        "resolver": resolved.resolver,
    });
    let _ = std::fs::write(path(dir), json.to_string());
}

/// Load a still-usable cached ECH set. Entries older than the bound are
/// rejected so a stale key cannot be reused indefinitely.
pub fn read(cache_dir: Option<&str>) -> Option<Resolved> {
    let dir = cache_dir?;
    let bytes = std::fs::read(path(dir)).ok()?;
    let value: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    let saved_at = value["saved_at"].as_u64()?;
    if now_secs().saturating_sub(saved_at) > CACHE_MAX_AGE_SECS {
        return None;
    }
    let ech_b64 = value["ech_b64"].as_str()?;
    let ech = base64::engine::general_purpose::STANDARD
        .decode(ech_b64)
        .ok()?;
    // Re-validate the cached ECH before trusting it.
    if EchConfig::new(
        EchConfigListBytes::from(ech.clone()),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .is_err()
    {
        return None;
    }
    let mut ips: Vec<Ipv4Addr> = value["ips"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str()?.parse::<Ipv4Addr>().ok())
                .filter(public_v4)
                .collect()
        })
        .unwrap_or_default();
    ips.truncate(4);
    if ips.is_empty() {
        return None;
    }
    let base_resolver = value["resolver"].as_str().unwrap_or("");
    Some(Resolved {
        ips,
        ech,
        resolver: format!("{base_resolver} · 本地缓存"),
    })
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Minimal ECH list that passes EchConfig::new validation.
    fn ech_fixture() -> Vec<u8> {
        let mut content = vec![1, 0, 32, 0, 32];
        content.extend([9].into_iter().chain([0u8; 31]));
        content.extend([0, 4, 0, 1, 0, 1, 0, 11]);
        content.extend(b"example.com");
        content.extend([0, 0]);
        let mut config = vec![0xfe, 0x0d];
        config.extend((content.len() as u16).to_be_bytes());
        config.extend(content);
        let mut list = (config.len() as u16).to_be_bytes().to_vec();
        list.extend(config);
        list
    }

    #[test]
    fn write_then_read_roundtrips() {
        let dir = std::env::temp_dir().join("yilan-cache-roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        let dir_str = dir.to_str().unwrap();
        let resolved = Resolved {
            ips: vec![Ipv4Addr::new(104, 20, 16, 234)],
            ech: ech_fixture(),
            resolver: "test-resolver".into(),
        };
        write(Some(dir_str), &resolved);
        let back = read(Some(dir_str)).expect("cache read must succeed");
        assert_eq!(back.ips, resolved.ips);
        assert_eq!(back.ech, resolved.ech);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn none_dir_is_noop_and_missing_file_returns_none() {
        let resolved = Resolved {
            ips: vec![Ipv4Addr::new(104, 20, 16, 234)],
            ech: ech_fixture(),
            resolver: "test".into(),
        };
        write(None, &resolved);
        assert!(read(None).is_none());
        let empty = std::env::temp_dir().join("yilan-cache-missing");
        std::fs::remove_dir_all(&empty).ok();
        std::fs::create_dir_all(&empty).unwrap();
        assert!(read(Some(empty.to_str().unwrap())).is_none());
        std::fs::remove_dir_all(&empty).ok();
    }
}
