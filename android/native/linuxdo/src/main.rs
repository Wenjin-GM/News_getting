//! Developer diagnostic only. The APK uses JNI, never this executable.
fn main() -> anyhow::Result<()> {
    if std::env::args().nth(1).as_deref() == Some("dns") {
        let report = yilan_linuxdo::diagnose_dns()?;
        let usable = report["resolvers"].as_array().is_some_and(|rows| rows.iter().any(|row| row["usable"] == true));
        println!("{report}");
        if !usable { std::process::exit(1); }
        return Ok(());
    }
    let endpoint = match std::env::args().nth(1).as_deref() {
        None | Some("rss") => "https://linux.do/latest.rss",
        Some("json") => "https://linux.do/latest.json",
        _ => anyhow::bail!("Usage: linuxdo-probe [rss|json|dns]"),
    };
    let response = match yilan_linuxdo::fetch(endpoint, None) {
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
        "tlsCertificateVerified":true,"httpProxyUsed":false,"systemHostsUsedForLinuxDo":false,
        "contentType":response.content_type,"bytes":response.body.len(),
        "jsonTopics":topics,"rssItems":rss_items}));
    Ok(())
}
