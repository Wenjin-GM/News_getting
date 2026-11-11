//! Direct, anonymous, fixed-host ECH client. No listener, CA, Cookie or WebView.
use anyhow::{anyhow, bail, ensure, Context, Result};
use bytes::Bytes;
use http_body_util::{BodyExt, Empty};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rustls::client::{EchConfig, EchStatus};
use rustls::pki_types::{EchConfigListBytes, ServerName};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
mod cache;
mod dns;
mod fallback;
#[cfg(test)] mod test_support;

/// Non-default modes are developer probes; JNI always selects Auto.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum DnsMode { Auto, Fallback, Cache }

const HOST: &str = "linux.do";
const LIMIT: usize = 5 * 1024 * 1024;
const DNS_LIMIT: usize = 65535;
const DNS_ID: u16 = 0;

pub struct Fetched {
    pub body: Vec<u8>,
    pub content_type: String,
    pub dns_source: String,
    pub from_cache: bool,
    pub status: u16,
}

#[derive(Debug)]
pub struct AccessVerification { pub status: u16 }
impl std::fmt::Display for AccessVerification {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "linux.do 需要访问验证（HTTP {}）；匿名获取已停止", self.status)
    }
}
impl std::error::Error for AccessVerification {}

fn allowed_path(endpoint: &str) -> Result<&'static str> {
    match endpoint {
        "https://linux.do/latest.rss" => Ok("/latest.rss"),
        "https://linux.do/latest.json" => Ok("/latest.json"),
        _ => bail!("linux.do endpoint is not allowed"),
    }
}

/// One bounded request. ECH must be accepted before any HTTP request is sent.
pub fn fetch(endpoint: &str, cache_dir: Option<&str>) -> Result<Fetched> {
    fetch_mode(endpoint, cache_dir, DnsMode::Auto)
}

pub fn fetch_mode(endpoint: &str, cache_dir: Option<&str>, mode: DnsMode) -> Result<Fetched> {
    let path = allowed_path(endpoint)?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let result = runtime.block_on(async {
            tokio::time::timeout(Duration::from_secs(90), fetch_inner(path, cache_dir, mode))
                .await.context("linux.do 加密连接超时")?
        });
    // OS getaddrinfo is not cancellable. Do not wait indefinitely for a
    // cancelled system-DNS fallback after the bounded async request ends.
    runtime.shutdown_timeout(Duration::from_millis(100));
    result
}

pub fn diagnose_dns() -> Result<serde_json::Value> {
    diagnose_dns_mode(None, DnsMode::Auto)
}

pub fn diagnose_dns_mode(cache_dir: Option<&str>, mode: DnsMode) -> Result<serde_json::Value> {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let result = runtime.block_on(dns::diagnose(cache_dir, mode));
    runtime.shutdown_timeout(Duration::from_millis(100));
    Ok(result)
}

pub fn dns_error_details(error: &anyhow::Error) -> Option<&serde_json::Value> {
    error.downcast_ref::<dns::DnsFailure>().map(|failure| &failure.details)
}

async fn fetch_inner(path: &str, cache_dir: Option<&str>, mode: DnsMode) -> Result<Fetched> {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let resolved = dns::resolve(cache_dir, mode).await?;
    let mut last = anyhow!("没有可用的站点地址");
    for ip in resolved.ips.iter().copied() {
        match tokio::time::timeout(Duration::from_secs(10), send(ip, &resolved.ech, path)).await {
            Ok(Ok(mut response)) => {
                cache::write(cache_dir, &resolved);
                response.dns_source = resolved.resolver;
                response.from_cache = resolved.from_cache;
                return Ok(response);
            }
            Ok(Err(error)) if error.is::<AccessVerification>() =>
                return Err(error.context(format!("DNS 已解析（{}）", resolved.resolver))),
            Ok(Err(error)) => last = error,
            Err(_) => last = anyhow!("站点连接超时"),
        }
    }
    Err(last.context(format!("DNS 已解析（{}）", resolved.resolver)))
}

fn public_v4(ip: &Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && (64..=127).contains(&b))
      || (a == 169 && b == 254) || (a == 172 && (16..=31).contains(&b))
      || (a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 0 && c == 2)))
      || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
      || (a == 203 && b == 0 && c == 113))
}

async fn send(ip: Ipv4Addr, ech_bytes: &[u8], path: &str) -> Result<Fetched> {
    let ech = EchConfig::new(EchConfigListBytes::from(ech_bytes.to_vec()),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES)?;
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = rustls::ClientConfig::builder_with_provider(
        rustls::crypto::aws_lc_rs::default_provider().into())
        .with_ech(ech.into())?.with_root_certificates(roots).with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    let tcp = tokio::net::TcpStream::connect(SocketAddr::new(IpAddr::V4(ip), 443)).await?;
    let tls = tokio_rustls::TlsConnector::from(Arc::new(config))
        .connect(ServerName::try_from(HOST.to_owned())?, tcp).await
        .context("linux.do ECH/certificate verification failed")?;
    ensure!(tls.get_ref().1.ech_status() == EchStatus::Accepted, "Server did not accept ECH");
    ensure!(tls.get_ref().1.alpn_protocol() == Some(b"h2"), "linux.do 未协商 HTTP/2；已停止");
    let (mut sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await?;
    let task = tokio::spawn(async move { let _ = connection.await; });
    // Abort even on errors; no background listener or leaked connection task.
    struct Abort(tokio::task::JoinHandle<()>);
    impl Drop for Abort { fn drop(&mut self) { self.0.abort(); } }
    let _connection = Abort(task);
    let request = hyper::Request::builder().uri(path).header("Host", HOST)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .header("Accept", "application/rss+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("Accept-Encoding", "identity")
        .body(Empty::<Bytes>::new())?;
    let response = sender.send_request(request).await?;
    if response.headers().get("cf-mitigated").and_then(|v| v.to_str().ok()) == Some("challenge")
        || response.status().as_u16() == 403 {
        return Err(AccessVerification { status: response.status().as_u16() }.into());
    }
    ensure!(response.status().is_success(), "linux.do HTTP {}", response.status().as_u16());
    // No redirect, login, cookies, script execution or silent non-ECH fallback.
    ensure!(response.headers().get("content-encoding").map(|v| v == "identity").unwrap_or(true),
        "Unexpected content encoding");
    let content_type = response.headers().get("content-type").and_then(|v| v.to_str().ok())
        .unwrap_or("").to_owned();
    ensure!(content_type.starts_with("application/rss+xml") || content_type.starts_with("application/json")
        || content_type.starts_with("application/xml") || content_type.starts_with("text/xml"),
        "linux.do returned a non-feed response; access verification may be required");
    let status = response.status().as_u16();
    let mut incoming = response.into_body();
    let mut body = Vec::new();
    while let Some(frame) = incoming.frame().await {
        if let Ok(chunk) = frame?.into_data() {
            ensure!(body.len() + chunk.len() <= LIMIT, "linux.do response exceeds 5 MiB");
            body.extend_from_slice(&chunk);
        }
    }
    ensure!(!body.is_empty(), "linux.do returned an empty response");
    Ok(Fetched { body, content_type, dns_source: String::new(), from_cache: false, status })
}

fn dns_request(kind: u16) -> Vec<u8> {
    let mut data = vec![0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in HOST.split('.') { data.push(label.len() as u8); data.extend_from_slice(label.as_bytes()); }
    data.push(0); data.extend_from_slice(&kind.to_be_bytes()); data.extend_from_slice(&[0, 1]);
    data
}

fn u16_at(data: &[u8], p: usize) -> Result<u16> {
    let b = data.get(p..p + 2).context("Truncated DNS field")?;
    Ok(u16::from_be_bytes([b[0], b[1]]))
}

fn skip_name(data: &[u8], cursor: &mut usize) -> Result<()> {
    for _ in 0..128 {
        let length = *data.get(*cursor).context("Truncated DNS name")?;
        *cursor += 1;
        if length == 0 { return Ok(()); }
        if length & 0xc0 == 0xc0 {
            let low = *data.get(*cursor).context("Truncated DNS pointer")?;
            ensure!(((((length & 0x3f) as usize) << 8) | low as usize) < data.len(), "Invalid DNS pointer");
            *cursor += 1; return Ok(());
        }
        ensure!(length <= 63 && *cursor + length as usize <= data.len(), "Invalid DNS label");
        *cursor += length as usize;
    }
    bail!("DNS name too long")
}

fn dns_records(data: &[u8], kind: u16) -> Result<Vec<(u16, Vec<u8>)>> {
    ensure!(data.len() >= 12 && u16_at(data, 0)? == DNS_ID, "Invalid DoH response ID/header");
    ensure!(u16_at(data, 2)? & 0x820f == 0x8000, "DNS error or truncated response");
    ensure!(u16_at(data, 4)? == 1, "Unexpected DNS question count");
    let mut cursor = 12;
    let question = cursor;
    skip_name(data, &mut cursor)?;
    let query = dns_request(kind);
    ensure!(data.get(question..cursor + 4) == Some(&query[12..]), "Wrong DNS question");
    cursor += 4;
    let mut records = Vec::new();
    for _ in 0..u16_at(data, 6)? {
        skip_name(data, &mut cursor)?;
        let rtype = u16_at(data, cursor)?;
        let class = u16_at(data, cursor + 2)?;
        let length = u16_at(data, cursor + 8)? as usize;
        cursor += 10;
        let value = data.get(cursor..cursor + length).context("Truncated DNS record")?;
        if class == 1 && rtype == kind { records.push((rtype, value.to_vec())); }
        cursor += length;
    }
    Ok(records)
}

struct HttpsBinding { ech: Option<Vec<u8>>, ips: Vec<Ipv4Addr> }
fn https_binding(data: &[u8]) -> Result<HttpsBinding> {
    ensure!(u16_at(data, 0)? > 0, "HTTPS alias mode is unsupported");
    // This client connects only to linux.do: non-root alternative targets are
    // deliberately not followed, nor are embedded IP hints trusted implicitly.
    ensure!(data.get(2) == Some(&0), "HTTPS alternative target is unsupported");
    let mut cursor = 3;
    let mut previous = None;
    let mut ech = None;
    let mut ips = Vec::new();
    let mut mandatory = Vec::new();
    let mut keys = Vec::new();
    while cursor < data.len() {
        let key = u16_at(data, cursor)?;
        ensure!(previous.map(|p| p < key).unwrap_or(true), "Invalid SvcParam order");
        previous = Some(key);
        keys.push(key);
        let length = u16_at(data, cursor + 2)? as usize;
        cursor += 4;
        let value = data.get(cursor..cursor + length).context("Truncated SvcParam")?;
        if key == 0 {
            ensure!(length > 0 && length % 2 == 0, "Invalid mandatory parameters");
            for item in value.chunks_exact(2) { mandatory.push(u16::from_be_bytes([item[0],item[1]])); }
        }
        if key == 3 { ensure!(value == 443u16.to_be_bytes(), "Non-HTTPS service port"); }
        if key == 4 {
            ensure!(length > 0 && length % 4 == 0, "Invalid IPv4 hints");
            for item in value.chunks_exact(4) { ips.push(Ipv4Addr::new(item[0],item[1],item[2],item[3])); }
        }
        if key == 5 { ensure!(length >= 2, "Empty ECH config"); ech = Some(value.to_vec()); }
        cursor += length;
    }
    ensure!(mandatory.iter().all(|key| matches!(key, 3 | 4 | 5) && keys.contains(key)), "Unsupported mandatory SvcParam");
    Ok(HttpsBinding { ech, ips })
}

#[no_mangle]
pub extern "system" fn Java_cn_yilan_news_LinuxDoTransport_nativeGet(
    mut env: jni::JNIEnv, _class: jni::objects::JClass,
    endpoint: jni::objects::JString, cache_dir: jni::objects::JString,
) -> jni::sys::jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Vec<u8>> {
        let endpoint: String = env.get_string(&endpoint)?.into();
        let cache_dir: String = env.get_string(&cache_dir)?.into();
        let dir_opt = if cache_dir.is_empty() { None } else { Some(cache_dir.as_str()) };
        Ok(fetch(&endpoint, dir_opt)?.body)
    }));
    match result {
        Ok(Ok(bytes)) => env.byte_array_from_slice(&bytes).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut()),
        error => {
            let message = match error { Ok(Err(e)) => format!("{e:#}"), _ => "linux.do native request failed".into() };
            let _ = env.throw_new("java/io/IOException", message);
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reject_arbitrary_endpoints() {
        for value in ["http://linux.do/latest.rss", "https://linux.do/login", "https://github.com/latest.rss",
            "https://linux.do/latest.rss?x=1", "https://linux.do@127.0.0.1/latest.rss"] {
            assert!(allowed_path(value).is_err());
        }
    }
    #[test]
    fn reject_local_and_reserved_addresses() {
        for value in ["127.211.73.84", "10.1.2.3", "192.168.1.1", "169.254.0.1", "100.64.1.1", "0.0.0.0", "203.0.113.1", "224.1.1.1"] {
            assert!(!public_v4(&value.parse().unwrap()));
        }
        assert!(public_v4(&"104.18.26.233".parse().unwrap()));
    }
    #[test]
    fn dns_wire_and_bounds() {
        let mut response = dns_request(1);
        response[2] = 0x81; response[3] = 0x80; response[7] = 1;
        response.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 104, 18, 26, 233]);
        assert_eq!(dns_records(&response, 1).unwrap(), vec![(1, vec![104, 18, 26, 233])]);
        assert!(dns_records(&response, 65).is_err());
        for cut in 0..response.len() { assert!(dns_records(&response[..cut], 1).is_err()); }
        response[3] = 0x83; assert!(dns_records(&response, 1).is_err());
    }
    #[test]
    fn ech_svcparam_bounds() {
        let data = [0, 1, 0, 0, 5, 0, 3, 0, 1, 2];
        assert_eq!(https_binding(&data).unwrap().ech, Some(vec![0, 1, 2]));
        assert!(https_binding(&data[..9]).is_err());
        assert!(https_binding(&[0, 0, 0]).is_err());
        assert_eq!(https_binding(&[0, 1, 0]).unwrap().ech, None);
    }
}
