//! Developer diagnostic only. The APK uses JNI, never this executable.
fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let mode = match args.get(2).map(String::as_str).unwrap_or("auto") {
        "auto" => yilan_linuxdo::DnsMode::Auto,
        "fallback" => yilan_linuxdo::DnsMode::Fallback,
        "cache" => yilan_linuxdo::DnsMode::Cache,
        _ => anyhow::bail!("DNS mode must be auto, fallback or cache"),
    };
    let cache_dir = args.get(3).map(String::as_str);
    if args.get(1).map(String::as_str) == Some("dns") {
        let report = yilan_linuxdo::diagnose_dns_mode(cache_dir, mode)?;
        let usable = report["usable"] == true;
        println!("{report}");
        if !usable { std::process::exit(1); }
        return Ok(());
    }
    let endpoint = match args.get(1).map(String::as_str) {
        None | Some("rss") => "https://linux.do/latest.rss",
        Some("json") => "https://linux.do/latest.json",
        _ => anyhow::bail!("Usage: linuxdo-probe [rss|json|dns] [auto|fallback|cache] [cache-dir]"),
    };
    let response = match yilan_linuxdo::fetch_mode(endpoint, cache_dir, mode) {
        Ok(response) => response,
        Err(error) => {
            let verification = error.downcast_ref::<yilan_linuxdo::AccessVerification>();
            println!("{}", serde_json::json!({"endpoint":endpoint,"success":false,
                "echAccepted":verification.map(|_|true),"tlsCertificateVerified":verification.map(|_|true),
                "httpStatus":verification.map(|v|v.status),"dnsDetails":yilan_linuxdo::dns_error_details(&error),"error":format!("{error:#}")}));
            std::process::exit(if verification.is_some() {2} else {1});
        }
    };
    let topics = serde_json::from_slice::<serde_json::Value>(&response.body).ok()
        .and_then(|v| v.get("topic_list")?.get("topics")?.as_array().map(Vec::len));
    let rss_items = String::from_utf8_lossy(&response.body).matches("<item>").count();
    println!("{}", serde_json::json!({"endpoint":endpoint,"ech":"accepted",
        "dnsSource":response.dns_source,"cacheUsed":response.from_cache,"httpStatus":response.status,"protocol":"HTTP/2",
        "tlsCertificateVerified":true,"httpProxyUsed":false,"systemHostsUsedForLinuxDo":false,
        "contentType":response.content_type,"bytes":response.body.len(),
        "jsonTopics":topics,"rssItems":rss_items}));
    Ok(())
}
