//! Bounded public DoH failover. Every route verifies its actual URL identity.
use super::*;
use base64::Engine;
use std::time::Instant;
use tokio::task::JoinSet;

#[derive(Clone, Copy)]
struct Resolver {
    name: &'static str,
    host: &'static str,
    ips: &'static [&'static str],
    ip_urls: bool,
}

// Operator-published anycast addresses, not cached linux.do IPs/ECH keys.
const RESOLVERS: [Resolver; 6] = [
    // These IP URLs present publicly trusted certificates with IP SANs.
    // rustls verifies the IP identity and does not send a DNS-name SNI.
    Resolver { name: "Cloudflare", host: "cloudflare-dns.com", ips: &["1.1.1.1", "1.0.0.1"], ip_urls: true },
    Resolver { name: "Google", host: "dns.google", ips: &["8.8.8.8", "8.8.4.4"], ip_urls: true },
    Resolver { name: "OpenDNS", host: "doh.opendns.com", ips: &["146.112.41.2"], ip_urls: false },
    Resolver { name: "AdGuard", host: "dns.adguard-dns.com", ips: &["94.140.14.14", "94.140.15.15"], ip_urls: false },
    Resolver { name: "阿里", host: "dns.alidns.com", ips: &["223.5.5.5", "223.6.6.6"], ip_urls: false },
    // DNSPod deprecated IP URLs. Use its published domain, no stale IP URI.
    Resolver { name: "DNSPod", host: "doh.pub", ips: &[], ip_urls: false },
];

const QUERY_BUDGET: Duration = Duration::from_secs(5);

#[derive(Clone, Copy, Debug, PartialEq)]
enum Route { Ip(&'static str), Bootstrap, SystemDns }
impl Route {
    fn label(self) -> &'static str {
        match self { Self::Ip(_) => "https-ip", Self::Bootstrap => "official-bootstrap", Self::SystemDns => "system-dns" }
    }
    fn authority(self, resolver: Resolver) -> &'static str {
        match self { Self::Ip(ip) => ip, _ => resolver.host }
    }
}

fn routes(resolver: Resolver) -> Vec<Route> {
    let mut out = Vec::new();
    if resolver.ip_urls { out.extend(resolver.ips.iter().copied().map(Route::Ip)); }
    if !resolver.ips.is_empty() { out.push(Route::Bootstrap); }
    out.push(Route::SystemDns);
    out
}

#[derive(Debug)]
pub struct Resolved {
    pub ips: Vec<Ipv4Addr>,
    pub ech: Vec<u8>,
    pub resolver: String,
    pub from_cache: bool,
}

struct Attempt {
    resolved: Option<Resolved>,
    brief: String,
    details: serde_json::Value,
}

#[derive(Debug)]
pub struct DnsFailure {
    pub details: serde_json::Value,
    summary: String,
}
impl std::fmt::Display for DnsFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "加密 DNS 不可用：{}", self.summary)
    }
}
impl std::error::Error for DnsFailure {}

fn client(resolver: Resolver, route: Route) -> Result<reqwest::Client> {
    let mut builder = reqwest::Client::builder().no_proxy().http1_only()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(4)).timeout(QUERY_BUDGET);
    if route == Route::Bootstrap && !resolver.ips.is_empty() {
        let addresses: Vec<SocketAddr> = resolver.ips.iter()
            .map(|ip| Ok(SocketAddr::new(IpAddr::V4(ip.parse()?), 443)))
            .collect::<Result<_>>()?;
        // URL, Host, SNI and certificate validation still use resolver.host.
        builder = builder.resolve_to_addrs(resolver.host, &addresses);
    }
    Ok(builder.build()?)
}

async fn query(http: &reqwest::Client, host: &str, resolver: Resolver, route: Route, kind: u16) -> Result<Vec<(u16, Vec<u8>)>> {
    let url = format!("https://{}/dns-query", route.authority(resolver));
    let payload = dns_request(host, kind);
    let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&payload);
    let mut request = http.get(&url).query(&[("dns", &encoded)])
        .header("Accept", "application/dns-message");
    // All listed endpoints implement RFC 8484. A method rejection gets one
    // POST fallback; resets/timeouts switch route/provider rather than loop.
    for method in 0..2 {
        let mut response = request.send().await?;
        if method == 0 && matches!(response.status().as_u16(), 400 | 405 | 415) {
            request = http.post(&url).header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message").body(payload.clone());
            continue;
        }
        response = response.error_for_status()?;
        ensure!(response.headers().get("content-type").and_then(|v| v.to_str().ok())
            .unwrap_or("").split(';').next().unwrap_or("").trim()
            .eq_ignore_ascii_case("application/dns-message"), "DoH 返回类型错误");
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            ensure!(bytes.len() + chunk.len() <= DNS_LIMIT, "DoH 响应超过上限");
            bytes.extend_from_slice(&chunk);
        }
        return dns_records(host, &bytes, kind);
    }
    bail!("DoH 请求方法不受支持")
}

fn reason(error: &anyhow::Error) -> &'static str {
    if error.to_string().contains("超时") { return "连接超时"; }
    if let Some(http) = error.downcast_ref::<reqwest::Error>() {
        if http.is_timeout() { return "连接超时"; }
        let chain = format!("{error:#}").to_ascii_lowercase();
        if chain.contains("certificate") || chain.contains("certvalid") {
            return "证书校验失败";
        }
        if chain.contains("reset") || chain.contains("forcibly closed") { return "连接重置"; }
        if http.is_connect() { return "连接失败"; }
        if http.is_status() { return "HTTP 拒绝"; }
    }
    "响应无效"
}

async fn query_with_deadline<T>(work: impl std::future::Future<Output = Result<T>>, budget: Duration) -> Result<T> {
    tokio::time::timeout(budget, work).await.context("查询连接超时")?
}

fn select_records(
    resolver: Resolver,
    addresses: &Result<Vec<(u16, Vec<u8>)>>,
    records: &Result<Vec<(u16, Vec<u8>)>>,
) -> Result<Resolved> {
    let records = records.as_ref().map_err(|e| anyhow!("HTTPS 查询{}", reason(e)))?;
    let mut bindings: Vec<(u16, HttpsBinding)> = records.iter().filter(|(kind, _)| *kind == 65)
        .filter_map(|(_, data)| https_binding(data).ok().map(|b| (u16_at(data, 0).unwrap_or(65535), b)))
        .collect();
    bindings.sort_by_key(|(priority, _)| *priority);
    for (_, binding) in bindings {
        let Some(ech) = binding.ech else { continue; };
        if EchConfig::new(EchConfigListBytes::from(ech.clone()),
            rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES).is_err() { continue; }
        let mut ips: Vec<Ipv4Addr> = addresses.as_ref().ok().into_iter().flatten()
            .filter(|(kind, _)| *kind == 1)
            .filter_map(|(_, value)| <[u8; 4]>::try_from(value.as_slice()).ok())
            .map(Ipv4Addr::from).filter(public_v4).collect();
        for ip in binding.ips { if public_v4(&ip) && !ips.contains(&ip) { ips.push(ip); } }
        ips.truncate(4);
        if !ips.is_empty() { return Ok(Resolved { ips, ech, resolver: resolver.name.into(), from_cache: false }); }
        bail!("ECH 已取得，但没有公网地址");
    }
    bail!("未提供有效 ECH 配置")
}

async fn attempt(host: &str, resolver: Resolver) -> Attempt {
    let started = Instant::now();
    let mut reports = Vec::new();
    let mut brief = "连接失败".to_string();
    for route in routes(resolver) {
        let http = match client(resolver, route) {
            Ok(http) => http,
            Err(error) => { brief = reason(&error).into(); break; }
        };
        // Each parallel query includes its GET -> POST fallback in the
        // deadline. Keep a completed HTTPS reply if only A times out: its
        // validated address hints can still supply the destination.
        let (addresses, records) = tokio::join!(
            query_with_deadline(query(&http, host, resolver, route, 1), QUERY_BUDGET),
            query_with_deadline(query(&http, host, resolver, route, 65), QUERY_BUDGET));
        let selected = select_records(resolver, &addresses, &records);
        reports.push(serde_json::json!({"route":route.label(),"authority":route.authority(resolver),
            "a":addresses.as_ref().map(|r|r.len()).map_err(|e|format!("{e:#}")),
            "https":records.as_ref().map(|r|r.len()).map_err(|e|format!("{e:#}")),
            "result":selected.as_ref().map(|r|serde_json::json!({"addresses":r.ips,"echBytes":r.ech.len()}))
                .map_err(|e|format!("{e:#}"))}));
        match selected {
            Ok(mut resolved) => {
                resolved.resolver = format!("{} · {}", resolver.name, route.authority(resolver));
                return Attempt { resolved: Some(resolved), brief: "已取得地址及 ECH".into(),
                details: serde_json::json!({"resolver":resolver.name,"host":host,"ms":started.elapsed().as_millis(),"routes":reports,"usable":true}) };
            }
            Err(error) => brief = error.to_string(),
        }
        // An authenticated, valid DNS response missing ECH is a data result,
        // not a connection reset. Don't waste another route on the same data.
        if addresses.is_ok() && records.is_ok() { break; }
    }
    Attempt { resolved: None, brief,
        details: serde_json::json!({"resolver":resolver.name,"host":host,"ms":started.elapsed().as_millis(),"routes":reports,"usable":false}) }
}

async fn bounded_attempt(host: &str, resolver: Resolver) -> Attempt {
    let budget = QUERY_BUDGET * routes(resolver).len() as u32 + Duration::from_secs(1);
    match tokio::time::timeout(budget, attempt(host, resolver)).await {
        Ok(attempt) => attempt,
        Err(_) => Attempt { resolved: None, brief: "连接超时".into(),
            details: serde_json::json!({"resolver":resolver.name,"host":host,"usable":false,"error":"resolver budget exceeded"}) },
    }
}

async fn gather(host: &str, stop_on_success: bool) -> (Option<Resolved>, Vec<serde_json::Value>, Vec<String>) {
    // Capture an owned host so the worker stays 'static and no higher-ranked
    // borrowed-&str bound is needed inside gather_with.
    let target = host.to_owned();
    let work = move |resolver: Resolver| {
        let name = target.clone();
        async move { bounded_attempt(&name, resolver).await }
    };
    gather_with(stop_on_success, work).await
}

async fn gather_with<F, Fut>(stop_on_success: bool, work: F) -> (Option<Resolved>, Vec<serde_json::Value>, Vec<String>)
where F: Fn(Resolver) -> Fut + Clone + Send + 'static,
      Fut: std::future::Future<Output = Attempt> + Send + 'static {
    let mut jobs = JoinSet::new();
    let mut next = 0;
    let mut reports = Vec::new();
    let mut failures = Vec::new();
    // At most two resolvers / four A+HTTPS requests in flight.
    while next < RESOLVERS.len() || !jobs.is_empty() {
        while next < RESOLVERS.len() && jobs.len() < 2 {
            let resolver = RESOLVERS[next]; next += 1;
            let worker = work.clone();
            jobs.spawn(async move { (resolver.name, worker(resolver).await) });
        }
        if let Some(Ok((name, attempt))) = jobs.join_next().await {
            reports.push(attempt.details);
            if let Some(resolved) = attempt.resolved {
                if stop_on_success { return (Some(resolved), reports, failures); }
            } else { failures.push(format!("{name}：{}", attempt.brief)); }
        }
    }
    (None, reports, failures)
}

// With unchanged per-provider/per-attempt limits, standard DNS finishes in
// at most ~43s. Keep its full budget, all three 8s fallback attempts, and leave
// at least 20s of the 90s source deadline for cached addresses/site connections.
const PUBLIC_BUDGET: Duration = Duration::from_secs(45);
const FALLBACK_BUDGET: Duration = Duration::from_secs(25);

struct Trace {
    selected: Option<Resolved>,
    reports: Vec<serde_json::Value>,
    failures: Vec<String>,
}
type PublicResult = (Option<Resolved>, Vec<serde_json::Value>, Vec<String>);

async fn pipeline<P, F>(host: &str, cache_dir: Option<&str>, mode: DnsMode, public: P, fallback: F,
    public_budget: Duration, fallback_budget: Duration) -> Trace
where P: std::future::Future<Output=PublicResult>, F: std::future::Future<Output=Result<Resolved>> {
    let mut trace = Trace { selected:None, reports:Vec::new(), failures:Vec::new() };
    if mode == DnsMode::Auto {
        match tokio::time::timeout(public_budget, public).await {
            Ok((selected,reports,failures)) => {
                trace.reports=reports;trace.failures=failures;
                if selected.is_some() { trace.selected=selected;return trace; }
            }
            Err(_) => {
                trace.reports.push(serde_json::json!({"resolver":"public","usable":false,"error":"public phase timed out"}));
                trace.failures.push("公共 DNS 阶段超时".into());
            }
        }
    }
    if mode != DnsMode::Cache {
        let result = tokio::time::timeout(fallback_budget, fallback).await
            .context("第三方 DNS 阶段超时").and_then(|r|r);
        match result {
            Ok(resolved) => {
                trace.reports.push(serde_json::json!({"resolver":"third-party","usable":true,"source":resolved.resolver}));
                trace.selected=Some(resolved);return trace;
            }
            Err(error) => {
                trace.reports.push(serde_json::json!({"resolver":"third-party","usable":false,"error":format!("{error:#}")}));
                let brief = if error.is::<reqwest::Error>() { reason(&error).to_string() }
                    else { error.to_string().chars().take(48).collect() };
                trace.failures.insert(0, format!("第三方 DNS：{brief}"));
            }
        }
    }
    if mode == DnsMode::Fallback { return trace; }
    trace.selected=cache::read(host, cache_dir);
    trace.reports.push(serde_json::json!({"resolver":"cache","usable":trace.selected.is_some(),
        "configured":cache_dir.is_some()}));
    if trace.selected.is_none() { trace.failures.insert(0, "无有效本地 ECH 缓存".into()); }
    trace
}

async fn trace(host: &str, cache_dir: Option<&str>, mode: DnsMode) -> Trace {
    pipeline(host, cache_dir, mode, gather(host, true),
        crate::fallback::resolve(host), PUBLIC_BUDGET, FALLBACK_BUDGET).await
}

pub async fn resolve(host: &str, cache_dir: Option<&str>, mode: DnsMode) -> Result<Resolved> {
    let trace=trace(host, cache_dir, mode).await;
    trace.selected.ok_or_else(|| DnsFailure {
        details:serde_json::json!(trace.reports),summary:trace.failures.join("; ")
    }.into())
}

pub async fn diagnose(host: &str, cache_dir: Option<&str>, mode: DnsMode) -> serde_json::Value {
    let trace=trace(host, cache_dir,mode).await;
    serde_json::json!({"host":host,"stage":"dns-pipeline","usable":trace.selected.is_some(),
        "selected":trace.selected.as_ref().map(|r|&r.resolver),"resolvers":trace.reports,
        "failures":trace.failures})
}

#[cfg(test)]
mod tests {
    use super::*;
    fn ech_fixture() -> Vec<u8> {
        let mut content = vec![1, 0, 32, 0, 32];
        content.extend([9].into_iter().chain([0; 31]));
        content.extend([0, 4, 0, 1, 0, 1, 0, 11]);
        content.extend(b"example.com"); content.extend([0, 0]);
        let mut config = vec![0xfe, 0x0d];config.extend((content.len() as u16).to_be_bytes());config.extend(content);
        let mut list = (config.len() as u16).to_be_bytes().to_vec();list.extend(config);list
    }
    #[test]
    fn incomplete_domestic_response_is_not_dns_success() {
        let addresses = Ok(vec![(1, vec![104, 18, 26, 233])]);
        let missing = Ok(vec![(65, vec![0, 1, 0, 0, 4, 0, 4, 104, 18, 26, 233])]);
        assert!(select_records(RESOLVERS[0], &addresses, &missing).unwrap_err().to_string().contains("ECH"));
    }
    #[test]
    fn valid_ech_hints_survive_failed_a_query_and_private_addresses_are_rejected() {
        let config = ech_fixture();
        let mut data = vec![0, 1, 0, 0, 4, 0, 4, 104, 18, 26, 233, 0, 5];
        data.extend((config.len() as u16).to_be_bytes());data.extend(config);
        let records = Ok(vec![(65, data)]);
        let resolved = select_records(RESOLVERS[0], &Err(anyhow!("A reset")), &records).unwrap();
        assert_eq!(resolved.ips, vec![Ipv4Addr::new(104, 18, 26, 233)]);
        let mut invalid = records.unwrap();invalid[0].1[7..11].copy_from_slice(&[127, 0, 0, 1]);
        assert!(select_records(RESOLVERS[0], &Ok(vec![]), &Ok(invalid)).is_err());
    }
    #[test]
    fn get_encoding_and_bootstrap_keep_https_hostname() {
        let resolver = *RESOLVERS.iter().find(|r| r.name == "OpenDNS").unwrap();
        assert_eq!(resolver.host, "doh.opendns.com");
        let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(dns_request(HOST_LINUXDO, 65));
        assert!(!encoded.contains('='));
        assert_eq!(base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(encoded).unwrap(), dns_request(HOST_LINUXDO, 65));
        assert!(client(resolver, Route::Bootstrap).is_ok());
    }
    #[test]
    fn ip_urls_use_ip_identity_and_preserve_both_domain_fallbacks() {
        for resolver in RESOLVERS.into_iter().filter(|r| r.ip_urls) {
            let plan = routes(resolver);
            assert_eq!(plan, vec![Route::Ip(resolver.ips[0]), Route::Ip(resolver.ips[1]), Route::Bootstrap, Route::SystemDns]);
            for route in &plan[..2] {
                let identity = ServerName::try_from(route.authority(resolver)).unwrap();
                assert!(matches!(identity, ServerName::IpAddress(_)), "must verify an IP SAN, not a forged DNS identity");
                assert!(client(resolver, *route).is_ok());
            }
            assert_eq!(plan[2].authority(resolver), resolver.host);
            assert_eq!(plan[3].authority(resolver), resolver.host);
        }
        // IP URL support is opt-in: publishing an address alone does not
        // establish that the operator serves a valid IP certificate there.
        let dnspod = *RESOLVERS.iter().find(|r| r.name == "DNSPod").unwrap();
        assert_eq!(routes(dnspod), vec![Route::SystemDns]);
        for resolver in RESOLVERS.into_iter().filter(|r| !r.ip_urls) {
            assert!(!routes(resolver).iter().any(|r| matches!(r, Route::Ip(_))));
        }
    }
    #[test]
    fn route_deadline_keeps_timeout_distinct_from_invalid_response() {
        assert_eq!(reason(&anyhow!("查询连接超时")), "连接超时");
        assert_eq!(reason(&anyhow!("DoH 返回类型错误")), "响应无效");
    }
    #[tokio::test(flavor = "current_thread")]
    async fn a_timeout_does_not_discard_completed_ech_and_address_hints() {
        let ech = ech_fixture();
        let mut binding = vec![0, 1, 0, 0, 4, 0, 4, 104, 18, 26, 233, 0, 5];
        binding.extend((ech.len() as u16).to_be_bytes());binding.extend(ech);
        let budget = Duration::from_millis(5);
        let (addresses, records) = tokio::join!(
            query_with_deadline(std::future::pending(), budget),
            query_with_deadline(async { Ok(vec![(65, binding)]) }, budget));
        assert_eq!(reason(addresses.as_ref().unwrap_err()), "连接超时");
        let selected = select_records(RESOLVERS[0], &addresses, &records).unwrap();
        assert_eq!(selected.ips, vec![Ipv4Addr::new(104, 18, 26, 233)]);
    }
    #[tokio::test(flavor = "current_thread")]
    async fn missing_ech_does_not_stop_failover_and_inflight_workers_are_bounded() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let worker = { let active = active.clone(); let peak = peak.clone(); move |resolver: Resolver| {
            let active = active.clone(); let peak = peak.clone();
            async move {
                struct Guard(Arc<AtomicUsize>);
                impl Drop for Guard { fn drop(&mut self) { self.0.fetch_sub(1, Ordering::SeqCst); } }
                let count = active.fetch_add(1, Ordering::SeqCst) + 1;
                peak.fetch_max(count, Ordering::SeqCst);let _guard = Guard(active);
                if resolver.name == "Cloudflare" {
                    Attempt { resolved: None, brief: "未提供有效 ECH 配置".into(), details: serde_json::json!({"usable":false}) }
                } else if resolver.name == "Google" {
                    tokio::time::sleep(Duration::from_millis(2)).await;
                    Attempt { resolved: Some(Resolved { ips: vec![Ipv4Addr::new(104,18,26,233)], ech: ech_fixture(), resolver: resolver.name.into(), from_cache: false }), brief: String::new(), details: serde_json::json!({"usable":true}) }
                } else { std::future::pending::<Attempt>().await }
            }
        }};
        let (selected, _, failures) = gather_with(true, worker).await;
        assert_eq!(selected.unwrap().resolver, "Google");
        assert!(failures.iter().any(|error| error.contains("ECH")));
        assert!(peak.load(Ordering::SeqCst) <= 2);
        tokio::task::yield_now().await;
        assert_eq!(active.load(Ordering::SeqCst), 0, "unused workers must be cancelled");
    }
    #[tokio::test(flavor = "current_thread")]
    async fn complete_failure_preserves_every_resolvers_reason() {
        let worker = |_resolver: Resolver| async {
            Attempt { resolved: None, brief: "连接重置".into(), details: serde_json::json!({"usable":false}) }
        };
        let (selected, reports, failures) = gather_with(true, worker).await;
        assert!(selected.is_none());assert_eq!(reports.len(), RESOLVERS.len());
        for resolver in RESOLVERS { assert!(failures.iter().any(|error| error.starts_with(resolver.name))); }
    }
    fn sample() -> Resolved {
        Resolved { ips:vec![Ipv4Addr::new(104,20,16,234)], ech:ech_fixture(), resolver:"fixture".into(), from_cache:false }
    }
    fn public_failure() -> PublicResult {
        (None,vec![serde_json::json!({"resolver":"public-fixture","usable":false})],vec!["public failed".into()])
    }
    #[tokio::test(flavor = "current_thread")]
    async fn public_success_never_polls_third_party_or_writes_unverified_cache() {
        let dir=crate::test_support::TempDir::new();let called=std::cell::Cell::new(false);
        let outcome=pipeline(HOST_LINUXDO,Some(dir.str()),DnsMode::Auto,
            async {(Some(sample()),vec![],vec![])},
            async {called.set(true);Err(anyhow!("must not run"))},PUBLIC_BUDGET,FALLBACK_BUDGET).await;
        assert!(outcome.selected.is_some());assert!(!called.get());assert!(cache::read(HOST_LINUXDO,Some(dir.str())).is_none());
    }
    #[tokio::test(flavor = "current_thread")]
    async fn public_timeout_still_allows_the_live_fallback_to_succeed() {
        let outcome=pipeline(HOST_LINUXDO,None,DnsMode::Auto,std::future::pending(),async {Ok(sample())},
            Duration::from_millis(2),Duration::from_secs(1)).await;
        assert!(!outcome.selected.unwrap().from_cache);
        assert!(outcome.failures.iter().any(|s|s.contains("超时")));
        assert!(outcome.reports.iter().any(|r|r["resolver"]=="third-party" && r["usable"]==true));
    }
    #[tokio::test(flavor = "current_thread")]
    async fn both_slow_phases_leave_time_to_use_cache_and_preserve_errors() {
        let dir=crate::test_support::TempDir::new();cache::write(HOST_LINUXDO,Some(dir.str()),&sample());
        let outcome=tokio::time::timeout(Duration::from_secs(1),pipeline(HOST_LINUXDO,Some(dir.str()),DnsMode::Auto,
            std::future::pending(),std::future::pending(),Duration::from_millis(2),Duration::from_millis(2))).await.unwrap();
        assert!(outcome.selected.unwrap().from_cache);
        assert_eq!(outcome.failures.len(),2);
        for phase in ["public","third-party","cache"] {
            assert!(outcome.reports.iter().any(|r|r["resolver"]==phase));
        }
        // Keep the original limits intact and explicitly reserve site time.
        assert!(PUBLIC_BUDGET+FALLBACK_BUDGET+Duration::from_secs(20)<=Duration::from_secs(90));
    }
    #[tokio::test(flavor = "current_thread")]
    async fn missing_cache_reports_third_party_failure_instead_of_hiding_it() {
        let outcome=pipeline(HOST_LINUXDO,None,DnsMode::Auto,async {public_failure()},
            async {Err(anyhow!("fallback fixture error"))},PUBLIC_BUDGET,FALLBACK_BUDGET).await;
        assert!(outcome.selected.is_none());
        assert!(outcome.failures.iter().any(|s|s.contains("fallback fixture error")));
        assert!(outcome.reports.iter().any(|r|r["resolver"]=="cache" && r["usable"]==false));
    }
    #[tokio::test(flavor = "current_thread")]
    async fn cache_probe_is_network_free_and_fallback_probe_cannot_hide_behind_cache() {
        let dir=crate::test_support::TempDir::new();cache::write(HOST_LINUXDO,Some(dir.str()),&sample());
        let cached=pipeline(HOST_LINUXDO,Some(dir.str()),DnsMode::Cache,std::future::pending(),std::future::pending(),
            PUBLIC_BUDGET,FALLBACK_BUDGET).await;
        assert!(cached.selected.unwrap().from_cache);
        let forced=pipeline(HOST_LINUXDO,Some(dir.str()),DnsMode::Fallback,std::future::pending(),
            async {Err(anyhow!("forced failure"))},PUBLIC_BUDGET,FALLBACK_BUDGET).await;
        assert!(forced.selected.is_none());
    }
}
