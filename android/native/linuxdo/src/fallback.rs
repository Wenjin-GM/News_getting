//! The existing keyless JSON DoH fallback, with bounded HTTPS-only transport.
use super::*;
use super::dns::Resolved;
use base64::Engine;
use std::collections::BTreeMap;

const ENDPOINT: &str = "https://aaa.ddd.oaifree.com/query-dns";

fn same_origin_redirect(next: &reqwest::Url, previous: &[reqwest::Url]) -> bool {
    let original = reqwest::Url::parse(ENDPOINT).expect("fixed HTTPS URL");
    previous.len() <= 2 && next.scheme() == "https"
        && next.host_str() == original.host_str()
        && next.port_or_known_default() == Some(443)
        && next.username().is_empty() && next.password().is_none()
}

fn client() -> Result<reqwest::Client> {
    Ok(reqwest::Client::builder().no_proxy().https_only(true)
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if same_origin_redirect(attempt.url(), attempt.previous()) { attempt.follow() }
            else { attempt.error("已阻止第三方 DoH 不安全重定向") }
        }))
        // Keep the proven eight-second timeout and three attempts.
        .timeout(Duration::from_secs(8)).build()?)
}

pub async fn resolve(host: &str) -> Result<Resolved> {
    let http = client()?;
    let mut last = anyhow!("第三方 DoH 无响应");
    for _ in 0..3 {
        match attempt(&http, host).await { Ok(value) => return Ok(value), Err(error) => last = error }
    }
    Err(last)
}

async fn bounded_body(mut response: reqwest::Response) -> Result<Vec<u8>> {
    ensure!(response.content_length().map(|n| n <= DNS_LIMIT as u64).unwrap_or(true),
        "第三方 DoH 响应超过上限");
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await? {
        ensure!(bytes.len() + chunk.len() <= DNS_LIMIT, "第三方 DoH 响应超过上限");
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

async fn attempt(http: &reqwest::Client, host: &str) -> Result<Resolved> {
    let response = http.get(ENDPOINT).query(&[("name", host), ("type", "HTTPS")])
        .header("accept", "application/dns-json").send().await?.error_for_status()?;
    parse(host, &bounded_body(response).await?)
}

fn matches_host(host: &str, name: &str) -> bool {
    name.trim_end_matches('.').eq_ignore_ascii_case(host)
}

fn parse(host: &str, bytes: &[u8]) -> Result<Resolved> {
    ensure!(bytes.len() <= DNS_LIMIT, "第三方 DoH 响应超过上限");
    let value: serde_json::Value = serde_json::from_slice(bytes).context("第三方 DoH 响应无效")?;
    ensure!(value["Status"] == 0, "第三方 DoH 返回错误状态");
    if let Some(questions) = value["Question"].as_array() {
        ensure!(questions.len() == 1 && questions[0]["type"] == 65
            && questions[0]["name"].as_str().is_some_and(|n| matches_host(host, n)),
            "第三方 DoH 问题不匹配");
    }
    let answers = value["Answer"].as_array().context("第三方 DoH 未返回 HTTPS 记录")?;
    let mut last = anyhow!("第三方 DoH 未返回本站有效 HTTPS 记录");
    for row in answers.iter().filter(|r| r["type"] == 65
        && r["name"].as_str().is_some_and(|n| matches_host(host, n))) {
        if let Some(data) = row["data"].as_str() {
            match binding(data) { Ok(resolved) => return Ok(resolved), Err(error) => last = error }
        }
    }
    Err(last)
}

fn binding(data: &str) -> Result<Resolved> {
    let mut tokens = data.split_ascii_whitespace();
    ensure!(tokens.next().and_then(|v| v.parse::<u16>().ok()).is_some_and(|p| p > 0),
        "第三方 DoH 不支持别名记录");
    ensure!(tokens.next() == Some("."), "第三方 DoH 不支持其他目标");
    let mut fields = BTreeMap::new();
    for token in tokens {
        let (key, value) = token.split_once('=').unwrap_or((token, ""));
        let value = if value.starts_with('"') && value.ends_with('"') && value.len() >= 2 {
            &value[1..value.len()-1]
        } else { value };
        ensure!(fields.insert(key, value).is_none(), "第三方 DoH 参数重复");
    }
    if let Some(port) = fields.get("port") { ensure!(*port == "443", "第三方 DoH 端口不受支持"); }
    if let Some(mandatory) = fields.get("mandatory") {
        ensure!(!mandatory.is_empty() && mandatory.split(',').all(|key|
            matches!(key, "ech" | "ipv4hint" | "port") && fields.contains_key(key)),
            "第三方 DoH mandatory 参数不受支持");
    }
    let ech = base64::engine::general_purpose::STANDARD
        .decode(fields.get("ech").context("第三方 DoH 未提供 ECH")?)
        .context("ECH base64 解码失败")?;
    EchConfig::new(EchConfigListBytes::from(ech.clone()),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES)
        .context("第三方 DoH 提供了不受支持的 ECH")?;
    let mut ips = Vec::new();
    for ip in fields.get("ipv4hint").into_iter().flat_map(|v| v.split(','))
        .filter_map(|s| s.parse::<Ipv4Addr>().ok()).filter(public_v4) {
        if !ips.contains(&ip) && ips.len() < 4 { ips.push(ip); }
    }
    ensure!(!ips.is_empty(), "第三方 DoH 未提供公网地址");
    Ok(Resolved { ips, ech, resolver: "第三方 DoH · aaa.ddd.oaifree.com".into(), from_cache: false })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn binding_text() -> String {
        format!("1 . alpn=h3,h2 ipv4hint=104.20.16.234,127.0.0.1,104.20.16.234 ech={}",
            base64::engine::general_purpose::STANDARD.encode(crate::test_support::ech()))
    }
    fn response(data: &str) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({"Status":0,
            "Question":[{"name":"linux.do.","type":65}],
            "Answer":[{"name":"linux.do.","type":65,"data":data}]})).unwrap()
    }
    #[test]
    fn real_service_shape_and_quoted_fields_remain_compatible() {
        let text = binding_text();
        let resolved = parse(HOST_LINUXDO, &response(&text)).unwrap();
        assert_eq!(resolved.ips, vec![Ipv4Addr::new(104,20,16,234)]);
        assert_eq!(resolved.ech, crate::test_support::ech());
        let quoted = text.replace("ech=", "ech=\"") + "\"";
        assert!(parse(HOST_LINUXDO, &response(&quoted)).is_ok());
    }
    #[test]
    fn wrong_questions_owners_parameters_and_fake_ech_keys_are_rejected() {
        let text = binding_text();
        for bad in [text.replace("ech=", "fake_ech="), format!("{text} port=8443"),
            format!("{text} mandatory=unknown"), format!("{text} ech=AAAA"),
            text.replacen("1 .", "0 .", 1), text.replacen("1 .", "1 other.example", 1),
            text.replace("104.20.16.234", "127.0.0.1")] {
            assert!(parse(HOST_LINUXDO, &response(&bad)).is_err(), "{bad}");
        }
        let mut value: serde_json::Value = serde_json::from_slice(&response(&text)).unwrap();
        value["Answer"][0]["name"] = "other.example".into();
        assert!(parse(HOST_LINUXDO, &serde_json::to_vec(&value).unwrap()).is_err());
        value["Answer"][0]["name"] = "linux.do".into();
        value["Question"][0]["name"] = "other.example".into();
        assert!(parse(HOST_LINUXDO, &serde_json::to_vec(&value).unwrap()).is_err());
    }
    #[test]
    fn redirect_policy_preserves_only_bounded_same_origin_https() {
        let origin = reqwest::Url::parse(ENDPOINT).unwrap();
        assert!(same_origin_redirect(&origin.join("/query-dns/").unwrap(), &[origin.clone()]));
        for next in ["http://aaa.ddd.oaifree.com/query-dns", "https://other.example/",
            "https://aaa.ddd.oaifree.com:8443/", "https://user@aaa.ddd.oaifree.com/"] {
            assert!(!same_origin_redirect(&reqwest::Url::parse(next).unwrap(), &[origin.clone()]));
        }
        assert!(!same_origin_redirect(&origin, &[origin.clone(),origin.clone(),origin.clone()]));
    }
    #[tokio::test(flavor = "current_thread")]
    async fn body_limit_covers_declared_and_undeclared_lengths() {
        for header in [None, Some("1"), Some("999999")] {
            let mut response = hyper::Response::builder();
            if let Some(value) = header { response = response.header("content-length", value); }
            let response = reqwest::Response::from(response.body(vec![b'x'; DNS_LIMIT+1]).unwrap());
            assert!(bounded_body(response).await.is_err());
        }
        let response = reqwest::Response::from(hyper::Response::new(b"{}".to_vec()));
        assert_eq!(bounded_body(response).await.unwrap(), b"{}");
    }
}
