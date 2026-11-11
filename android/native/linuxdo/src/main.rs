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
        let report = yilan_linuxdo::diagnose_dns_mode(yilan_linuxdo::HOST_LINUXDO, cache_dir, mode)?;
        let usable = report["usable"] == true;
        println!("{report}");
        if !usable { std::process::exit(1); }
        return Ok(());
    }
    let endpoint = if let Ok(custom) = std::env::var("PROBE_URL") {
        custom
    } else {
        match args.get(1).map(String::as_str) {
            None | Some("rss") => "https://linux.do/latest.rss".to_string(),
            Some("views") => "https://linux.do/top.json?period=quarterly".to_string(),
            Some("latest") | Some("json") => "https://linux.do/latest.json?per_page=50".to_string(),
            Some("v2ex-hot") => "https://www.v2ex.com/api/topics/hot.json".to_string(),
            Some("v2ex-latest") => "https://www.v2ex.com/index.xml".to_string(),
            _ => anyhow::bail!(
                "Usage: linuxdo-probe [rss|views|latest|v2ex-hot|v2ex-latest|dns] [auto|fallback|cache] [cache-dir]"
            ),
        }
    };
    let response = match yilan_linuxdo::fetch_mode(&endpoint, cache_dir, mode) {
        Ok(response) => response,
        Err(error) => {
            let verification = error.downcast_ref::<yilan_linuxdo::AccessVerification>();
            println!("{}", serde_json::json!({"endpoint":endpoint,"success":false,
                "echAccepted":verification.map(|_|true),"tlsCertificateVerified":verification.map(|_|true),
                "httpStatus":verification.map(|v|v.status),"cfChallenge":verification.map(|v|v.challenged),"dnsDetails":yilan_linuxdo::dns_error_details(&error),"error":format!("{error:#}")}));
            std::process::exit(if verification.is_some() {2} else {1});
        }
    };
    let parsed = serde_json::from_slice::<serde_json::Value>(&response.body).ok();
    let topics = parsed.as_ref()
        .and_then(|v| v.get("topic_list")?.get("topics")?.as_array());
    let topic_count = topics.map(Vec::len);
    let view_stats = topics.map(|arr| {
        let views: Vec<u64> = arr.iter().filter_map(|t| t.get("views")?.as_u64()).collect();
        let min = views.iter().copied().min().unwrap_or(0);
        let max = views.iter().copied().max().unwrap_or(0);
        serde_json::json!({"count":views.len(),"min":min,"max":max})
    });
    let rss_items = String::from_utf8_lossy(&response.body).matches("<item>").count();
    println!("{}", serde_json::json!({"endpoint":endpoint,"ech":"accepted",
        "dnsSource":response.dns_source,"cacheUsed":response.from_cache,"httpStatus":response.status,"protocol":"HTTP/2",
        "tlsCertificateVerified":true,"httpProxyUsed":false,"systemHostsUsedForLinuxDo":false,
        "contentType":response.content_type,"bytes":response.body.len(),
        "jsonTopics":topic_count,"viewStats":view_stats,"rssItems":rss_items}));
    if let Ok(save) = std::env::var("PROBE_SAVE") {
        std::fs::write(&save, &response.body).ok();
    }
    Ok(())
}
