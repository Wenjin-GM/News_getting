package cn.yilan.news;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
public final class NativeEngineTest {
  private static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
  private static void rejects(NewsEngine.Source s,String input,long now)throws Exception{boolean failed=false;try{NewsEngine.parse(s,input,now);}catch(Exception e){failed=true;}check(failed,"Expected rejected feed");}
  public static void main(String[] args)throws Exception{
    NewsEngine engine=new NewsEngine(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
    NewsEngine.Source rss=engine.sourceMap.get("chinanews"),atom=engine.sourceMap.get("ai-codex");
    long now=Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();
    String xml="<rss><channel><item><title><![CDATA[Hello <b>AI</b>]]></title><link>https://example.com/a</link><description>&lt;p&gt;Safe summary&lt;/p&gt;&lt;script&gt;bad()&lt;/script&gt;</description><pubDate>Thu, 29 Oct 2026 18:00:00 +0800</pubDate></item><item><title>Unsafe link</title><link>javascript:alert(1)</link></item><item><title>Duplicate</title><link>https://example.com/a</link></item></channel></rss>";
    List<NewsEngine.Article> a=NewsEngine.parse(rss,xml,now);check(a.size()==1,"URL filtering/dedup");check(a.get(0).title.equals("Hello AI"),"Title cleanup");check(a.get(0).summary.equals("Safe summary"),"Summary cleanup");check(a.get(0).id.matches("[a-f0-9]{24}"),"Stable ID shape");check(a.get(0).id.equals(NewsEngine.parse(rss,xml,now).get(0).id),"Stable ID");
    String feed="<feed><entry><title>v1.2.0-alpha</title><link rel=\"self\" href=\"https://example.com/self\"/><link rel=\"alternate\" href=\"https://example.com/release\"/><updated>2026-10-28T10:00:00Z</updated><content type=\"html\">&lt;p&gt;Release notes&lt;/p&gt;</content></entry></feed>";
    NewsEngine.Article r=NewsEngine.parse(atom,feed,now).get(0);check(r.summary.equals("Release notes"),"Atom encoded HTML");check(r.url.endsWith("/release"),"Atom alternate link");
    rejects(rss,xml.replace("Thu, 29 Oct 2026","Wed, 29 Oct 2025"),now);rejects(rss,"<html>verify access</html>",now);
    String api="[{\"tag_name\":\"v1\",\"html_url\":\"https://example.com/release\",\"body\":\"Notes\",\"published_at\":\"2026-10-28\",\"prerelease\":true},{\"tag_name\":\"draft\",\"draft\":true}]";
    check(NewsEngine.parse(atom,api,now).size()==1,"Draft release skipped");
    for(String url:new String[]{"javascript:alert(1)","file:///etc/passwd","content://contacts/1","intent://open","http://localhost/","http://127.1/","http://0x7f000001/","http://2130706433/","https://[::1]/","https://192.168.0.1/","https://x.local/","https://metadata.google.internal/","https://user:pass@example.com/","https://example.com:8080/","https://example.com/evil.%61pk"})check(NewsEngine.publicURL(url)==null,"Unsafe URL accepted: "+url);
    check(NewsEngine.publicURL("https://example.com/a?x=1&amp;y=2").equals("https://example.com/a?x=1&y=2"),"Entity URL normalization");
    check(NewsEngine.Article.from(new JSONObject("{\"id\":\"x\",\"title\":{},\"url\":\"https://example.com\"}"))==null,"Object fields rejected");
    NewsEngine.Snapshot saved=new NewsEngine.Snapshot(rss.id);saved.items.addAll(a);saved.fetched=now;saved.checked=now;NewsEngine.Snapshot restored=NewsEngine.Snapshot.from(rss.id,saved.json());check(restored.items.size()==1&&restored.items.get(0).title.equals(a.get(0).title),"Offline snapshot round trip");
    System.out.println("PASS: native RSS/Atom/JSON, HTML cleanup, draft/stale rejection, URL safety, stable IDs and offline snapshot persistence");
    if(args.length>1&&args[1].equals("--live")){
      List<NewsEngine.Source> selected=new ArrayList<>();for(NewsEngine.Source s:engine.sources)if(s.defaultEnabled)selected.add(s);
      ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<JSONObject>> tasks=new ArrayList<>();
      for(NewsEngine.Source source:selected)tasks.add(pool.submit(()->{long start=System.currentTimeMillis();NewsEngine.Snapshot value=engine.fetch(source,null,false);JSONObject row=new JSONObject().put("id",source.id).put("items",value.items.size()).put("error",value.error).put("ms",System.currentTimeMillis()-start);System.out.println(source.id+": "+value.items.size()+(value.error.isEmpty()?"":" · "+value.error));return row;}));
      JSONArray checks=new JSONArray();int count=0,total=0;for(Future<JSONObject> task:tasks){JSONObject row=task.get();checks.put(row);if(row.optInt("items")>0)count++;total+=row.optInt("items");}pool.shutdown();JSONObject report=new JSONObject().put("checkedAt",Instant.now().toString()).put("runtime","Java HTTPS direct collector; no proxy/backend").put("sources",selected.size()).put("success",count).put("items",total).put("checks",checks);Files.write(Paths.get(args[2]),report.toString(2).getBytes(StandardCharsets.UTF_8));System.out.println("Native direct collection: "+count+"/"+selected.size()+" sources, "+total+" items");
    }
  }
}
