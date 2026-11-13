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

// Registered ECH hosts and their fixed, non-dynamic endpoints. Each host is
// served over encrypted SNI; the JNI signature stays unchanged because the
// endpoint string already carries the host.
pub const HOST_LINUXDO: &str = "linux.do";
pub const HOST_V2EX: &str = "www.v2ex.com";
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
pub struct AccessVerification { pub status: u16, pub challenged: bool, pub endpoint: String }
impl std::fmt::Display for AccessVerification {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let reason = if self.challenged { "站点要求访问验证；Cloudflare challenge 标记存在" }
            else { "服务器拒绝匿名请求；未发现 Cloudflare challenge 标记" };
        write!(f, "{}（HTTP {}）；匿名获取已停止；请求：{}", reason, self.status, self.endpoint)
    }
}
impl std::error::Error for AccessVerification {}

fn access_failure(host: &str, path: &str, status: u16, headers: &hyper::HeaderMap) -> Option<AccessVerification> {
    let challenged = headers.get("cf-mitigated").and_then(|v| v.to_str().ok()) == Some("challenge");
    (challenged || status == 403).then(|| AccessVerification {
        status, challenged, endpoint: format!("https://{host}{path}"),
    })
}

/// Validate the fixed host and path, then return them separately. Only https,
/// no user info/port, and an exact registered path are accepted.
fn parse_endpoint(endpoint: &str) -> Result<(String, String)> {
    let rest = endpoint
        .strip_prefix("https://")
        .context("only https endpoints are allowed")?;
    let (host, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    ensure!(!host.contains('@') && !host.contains(':'), "host must be a plain name");
    let fixed: &[&str] = match host {
        HOST_LINUXDO => &[
            "/latest.rss",
            "/top.json?period=quarterly",
            "/latest.json?per_page=50",
        ],
        HOST_V2EX => &["/api/topics/hot.json", "/index.xml"],
        _ => bail!("endpoint host is not registered"),
    };
    // linux.do single-topic full text: numeric id only, no slug/query/extra.
    let topic_ok = host == HOST_LINUXDO && {
        path.strip_prefix("/t/")
            .and_then(|v| v.strip_suffix(".json"))
            .is_some_and(|id| !id.is_empty() && id.bytes().all(|b| b.is_ascii_digit()))
    };
    ensure!(fixed.contains(&path) || topic_ok, "endpoint path is not allowed");
    Ok((host.to_string(), path.to_string()))
}

/// One bounded request. ECH must be accepted before any HTTP request is sent.
pub fn fetch(endpoint: &str, cache_dir: Option<&str>) -> Result<Fetched> {
    fetch_mode(endpoint, cache_dir, DnsMode::Auto)
}

pub fn fetch_mode(endpoint: &str, cache_dir: Option<&str>, mode: DnsMode) -> Result<Fetched> {
    let (host, path) = parse_endpoint(endpoint)?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let result = runtime.block_on(async {
            tokio::time::timeout(Duration::from_secs(90), fetch_inner(&host, &path, cache_dir, mode))
                .await.context("加密连接超时")?
        });
    // OS getaddrinfo is not cancellable. Do not wait indefinitely for a
    // cancelled system-DNS fallback after the bounded async request ends.
    runtime.shutdown_timeout(Duration::from_millis(100));
    result
}

pub fn diagnose_dns() -> Result<serde_json::Value> {
    diagnose_dns_mode(HOST_LINUXDO, None, DnsMode::Auto)
}

pub fn diagnose_dns_mode(host: &str, cache_dir: Option<&str>, mode: DnsMode) -> Result<serde_json::Value> {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let result = runtime.block_on(dns::diagnose(host, cache_dir, mode));
    runtime.shutdown_timeout(Duration::from_millis(100));
    Ok(result)
}

pub fn dns_error_details(error: &anyhow::Error) -> Option<&serde_json::Value> {
    error.downcast_ref::<dns::DnsFailure>().map(|failure| &failure.details)
}

async fn fetch_inner(host: &str, path: &str, cache_dir: Option<&str>, mode: DnsMode) -> Result<Fetched> {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let resolved = dns::resolve(host, cache_dir, mode).await?;
    let mut last = anyhow!("没有可用的站点地址");
    for ip in resolved.ips.iter().copied() {
        match tokio::time::timeout(Duration::from_secs(10), send(host, ip, &resolved.ech, path)).await {
            Ok(Ok(mut response)) => {
                cache::write(host, cache_dir, &resolved);
                response.dns_source = resolved.resolver;
                response.from_cache = resolved.from_cache;
                return Ok(response);
            }
            // AccessVerification also falls through to the next resolved edge IP:
            // distinct Cloudflare edges can apply different bot scores. The last
            // error (including its "DNS 已解析" context) is returned if all fail.
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

async fn send(host: &str, ip: Ipv4Addr, ech_bytes: &[u8], path: &str) -> Result<Fetched> {
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
        .connect(ServerName::try_from(host.to_owned())?, tcp).await
        .context("ECH/certificate verification failed")?;
    ensure!(tls.get_ref().1.ech_status() == EchStatus::Accepted, "Server did not accept ECH");
    ensure!(tls.get_ref().1.alpn_protocol() == Some(b"h2"), "未协商 HTTP/2；已停止");
    let (mut sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await?;
    let task = tokio::spawn(async move { let _ = connection.await; });
    // Abort even on errors; no background listener or leaked connection task.
    struct Abort(tokio::task::JoinHandle<()>);
    impl Drop for Abort { fn drop(&mut self) { self.0.abort(); } }
    let _connection = Abort(task);
    let request = feed_request(host, path)?;
    let response = sender.send_request(request).await?;
    if let Some(error) = access_failure(host, path, response.status().as_u16(), response.headers()) {
        return Err(error.into());
    }
    ensure!(response.status().is_success(), "HTTP {}", response.status().as_u16());
    // No redirect, login, cookies, script execution or silent non-ECH fallback.
    ensure!(response.headers().get("content-encoding").map(|v| v == "identity").unwrap_or(true),
        "Unexpected content encoding");
    let content_type = response.headers().get("content-type").and_then(|v| v.to_str().ok())
        .unwrap_or("").to_owned();
    ensure!(content_type.starts_with("application/rss+xml") || content_type.starts_with("application/atom+xml")
        || content_type.starts_with("application/json")
        || content_type.starts_with("application/xml") || content_type.starts_with("text/xml"),
        "returned a non-feed response; access verification may be required");
    let status = response.status().as_u16();
    let mut incoming = response.into_body();
    let mut body = Vec::new();
    while let Some(frame) = incoming.frame().await {
        if let Ok(chunk) = frame?.into_data() {
            ensure!(body.len() + chunk.len() <= LIMIT, "response exceeds 5 MiB");
            body.extend_from_slice(&chunk);
        }
    }
    ensure!(!body.is_empty(), "returned an empty response");
    Ok(Fetched { body, content_type, dns_source: String::new(), from_cache: false, status })
}

// Chrome 130 low-entropy client hints. The version must match the User-Agent
// below: a UA claiming Chrome 130 without sec-ch-ua is itself an automation
// signal Cloudflare uses for bot scoring.
const SEC_CH_UA: &str = r#""Chromium";v="130", "Google Chrome";v="130", "Not?A_Brand";v="99""#;

fn feed_request(host: &str, path: &str) -> Result<hyper::Request<Empty<Bytes>>> {
    // HTTP/2 requires the TLS origin in :scheme/:authority. With a path-only
    // URI, h2 defaults :scheme to http even though the socket uses TLS.
    let list_page = if host == HOST_LINUXDO {
        match path {
            "/top.json?period=quarterly" => Some("https://linux.do/top?period=quarterly".to_owned()),
            "/latest.json?per_page=50" => Some("https://linux.do/latest".to_owned()),
            _ if path.starts_with("/t/") && path.ends_with(".json") =>
                Some(format!("https://linux.do{}", path.trim_end_matches(".json"))),
            _ => None,
        }
    } else { None };
    let is_linuxdo = host == HOST_LINUXDO;
    let mut builder = hyper::Request::builder().uri(format!("https://{host}{path}")).header("Host", host)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36")
        .header("Accept", if list_page.is_some() { "application/json" } else { "application/json,application/rss+xml,application/xml;q=0.9,*/*;q=0.8" })
        .header("Accept-Language", "zh-CN,zh;q=0.9");
    if is_linuxdo {
        // Chrome 130 Android client hints, matching the declared UA version.
        builder = builder
            .header("Sec-Ch-Ua", SEC_CH_UA)
            .header("Sec-Ch-Ua-Mobile", "?1")
            .header("Sec-Ch-Ua-Platform", "\"Android\"");
    }
    if let Some(page) = list_page {
        // Anonymous Discourse list request; no credentials or challenge handling.
        builder = builder.header("Referer", page)
            .header("X-Requested-With", "XMLHttpRequest");
        if is_linuxdo {
            // Same-origin XHR fetch metadata a real Chrome sends after the page.
            builder = builder
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty");
        }
    } else if is_linuxdo {
        // The RSS feed is opened as a top-level feed/document navigation.
        builder = builder
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", "document");
    }
    builder = builder.header("Accept-Encoding", "identity");
    Ok(builder.body(Empty::<Bytes>::new())?)
}

fn dns_request(host: &str, kind: u16) -> Vec<u8> {
    let mut data = vec![0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in host.split('.') { data.push(label.len() as u8); data.extend_from_slice(label.as_bytes()); }
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

fn dns_records(host: &str, data: &[u8], kind: u16) -> Result<Vec<(u16, Vec<u8>)>> {
    ensure!(data.len() >= 12 && u16_at(data, 0)? == DNS_ID, "Invalid DoH response ID/header");
    ensure!(u16_at(data, 2)? & 0x820f == 0x8000, "DNS error or truncated response");
    ensure!(u16_at(data, 4)? == 1, "Unexpected DNS question count");
    let mut cursor = 12;
    let question = cursor;
    skip_name(data, &mut cursor)?;
    let query = dns_request(host, kind);
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
    fn challenge_marker_is_distinct_from_an_unclassified_403() {
        let mut headers = hyper::HeaderMap::new();
        assert!(access_failure(HOST_LINUXDO, "/latest.rss", 200, &headers).is_none());
        let denied = access_failure(HOST_V2EX, "/index.xml", 403, &headers).unwrap();
        assert!(!denied.challenged);
        assert!(denied.to_string().contains("https://www.v2ex.com/index.xml"));
        assert!(!denied.to_string().contains("https://linux.do"));
        headers.insert("cf-mitigated", "challenge".parse().unwrap());
        for status in [200, 403] {
            let challenge = access_failure(HOST_LINUXDO, "/top.json?period=quarterly", status, &headers).unwrap();
            assert!(challenge.challenged);
            assert!(challenge.to_string().contains("challenge 标记存在"));
        }
    }
    #[test]
    fn h2_pseudo_headers_use_the_verified_https_origin() {
        for host in [HOST_LINUXDO,HOST_V2EX] {
            let request=feed_request(host,"/test.json?q=1").unwrap();
            assert_eq!(request.uri().scheme_str(),Some("https"));
            assert_eq!(request.uri().authority().unwrap().as_str(),host);
            assert_eq!(request.uri().path_and_query().unwrap().as_str(),"/test.json?q=1");
            assert_eq!(request.headers()["Host"],host);
            assert!(!request.headers().contains_key("X-Requested-With"));
            assert!(!request.headers().contains_key("Referer"));
        }
        let quarterly = feed_request(HOST_LINUXDO,"/top.json?period=quarterly").unwrap();
        assert_eq!(quarterly.headers().get_all("Accept").iter().count(),1);
        assert_eq!(quarterly.headers()["Accept"],"application/json");
        assert_eq!(quarterly.headers()["Referer"],"https://linux.do/top?period=quarterly");
        assert_eq!(quarterly.headers()["X-Requested-With"],"XMLHttpRequest");
        let latest = feed_request(HOST_LINUXDO,"/latest.json?per_page=50").unwrap();
        assert_eq!(latest.headers()["Referer"],"https://linux.do/latest");
        assert_eq!(latest.headers()["X-Requested-With"],"XMLHttpRequest");
        let topic = feed_request(HOST_LINUXDO,"/t/2910500.json").unwrap();
        assert_eq!(topic.headers()["Referer"],"https://linux.do/t/2910500");
        assert_eq!(topic.headers()["X-Requested-With"],"XMLHttpRequest");
    }
    #[test]
    fn linuxdo_carries_chrome_client_hints_and_fetch_metadata() {
        // RSS feed: top-level navigation metadata plus client hints.
        let rss = feed_request(HOST_LINUXDO, "/latest.rss").unwrap();
        assert_eq!(rss.headers()["Sec-Ch-Ua"], SEC_CH_UA);
        assert_eq!(rss.headers()["Sec-Ch-Ua-Mobile"], "?1");
        assert_eq!(rss.headers()["Sec-Ch-Ua-Platform"], "\"Android\"");
        assert_eq!(rss.headers()["Sec-Fetch-Site"], "none");
        assert_eq!(rss.headers()["Sec-Fetch-Mode"], "navigate");
        assert_eq!(rss.headers()["Sec-Fetch-Dest"], "document");
        // JSON list/topic: same-origin XHR fetch metadata.
        for path in ["/top.json?period=quarterly", "/latest.json?per_page=50", "/t/2910500.json"] {
            let xhr = feed_request(HOST_LINUXDO, path).unwrap();
            assert_eq!(xhr.headers()["Sec-Fetch-Site"], "same-origin");
            assert_eq!(xhr.headers()["Sec-Fetch-Mode"], "cors");
            assert_eq!(xhr.headers()["Sec-Fetch-Dest"], "empty");
        }
        // V2EX keeps its original headers; no linux.do-specific client hints.
        for path in ["/api/topics/hot.json", "/index.xml"] {
            let v2ex = feed_request(HOST_V2EX, path).unwrap();
            assert!(!v2ex.headers().contains_key("Sec-Ch-Ua"));
            assert!(!v2ex.headers().contains_key("Sec-Fetch-Site"));
        }
    }
    #[test]
    fn reject_arbitrary_endpoints() {
        for value in ["http://linux.do/latest.rss", "https://linux.do/login", "https://github.com/latest.rss",
            "https://linux.do/latest.rss?x=1", "https://linux.do@127.0.0.1/latest.rss",
            "https://linux.do/top.json", "https://linux.do/top.json?period=daily",
            "https://linux.do/top.json?period=all", "https://linux.do/top.json?period=quarterly&x=1",
            "https://linux.do/latest.json", "https://linux.do/latest.json?per_page=50&x=1",
            "https://linux.do/t/abc.json", "https://linux.do/t/123.json?x=1",
            "https://linux.do/t/slug/123.json", "https://linux.do/t/.json",
            "https://www.v2ex.com/login", "https://www.v2ex.com/api/topics/show.json",
            "https://v2ex.com/api/topics/hot.json", "https://www.v2ex.com/index.xml?x=1"] {
            assert!(parse_endpoint(value).is_err(), "must reject {value}");
        }
        for value in ["https://linux.do/latest.rss", "https://linux.do/top.json?period=quarterly",
            "https://linux.do/latest.json?per_page=50", "https://linux.do/t/2910500.json",
            "https://www.v2ex.com/api/topics/hot.json", "https://www.v2ex.com/index.xml"] {
            assert!(parse_endpoint(value).is_ok(), "must allow {value}");
        }
        // parse_endpoint returns the host and the exact path.
        assert_eq!(parse_endpoint("https://www.v2ex.com/index.xml").unwrap(),
            ("www.v2ex.com".to_string(), "/index.xml".to_string()));
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
        let mut response = dns_request(HOST_LINUXDO, 1);
        response[2] = 0x81; response[3] = 0x80; response[7] = 1;
        response.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 104, 18, 26, 233]);
        assert_eq!(dns_records(HOST_LINUXDO, &response, 1).unwrap(), vec![(1, vec![104, 18, 26, 233])]);
        assert!(dns_records(HOST_LINUXDO, &response, 65).is_err());
        for cut in 0..response.len() { assert!(dns_records(HOST_LINUXDO, &response[..cut], 1).is_err()); }
        response[3] = 0x83; assert!(dns_records(HOST_LINUXDO, &response, 1).is_err());
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
