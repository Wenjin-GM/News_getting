package cn.yilan.news;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import cn.yilan.news.NewsEngine.*;

public final class AiEngineTest {
  private static final String KEY="unit-test-credential-not-a-real-key";
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  private static AiClient.Config config()throws Exception{return new AiClient.Config("https://api.deepseek.com",KEY,"chosen-model","profile-one",true);}
  private static String reply(String model,String text)throws Exception{return new JSONObject().put("model",model).put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content",text)))).toString();}
  private static Article article(String id){Article a=new Article();a.id=id;a.source="infoq-cn";a.sourceName="InfoQ";a.category="tech";a.title="AI news "+id;a.url="https://www.infoq.cn/article/"+id;a.summary="PRIVATE_BODY_SENTINEL";a.heat="PRIVATE_HEAT_SENTINEL";a.published=System.currentTimeMillis();return a;}
  private static void rejectsURL(String url)throws Exception{boolean rejected=false;try{AiClient.baseURL(url);}catch(AiClient.Failure e){rejected=true;}check(rejected,"Unsafe AI endpoint rejected");}
  public static void main(String[] args)throws Exception{
    check(AiClient.baseURL("https://open.bigmodel.cn/api/paas/v4/chat/completions").equals("https://open.bigmodel.cn/api/paas/v4"),"GLM path preserved");
    check(AiClient.baseURL("https://api.deepseek.com/").equals("https://api.deepseek.com"),"DeepSeek root does not acquire a forced v1");
    for(String url:new String[]{"http://api.deepseek.com","https://localhost/v1","https://127.0.0.1/v1","https://user:secret@example.com","https://example.com/v1?api_key=secret","file:///private"})rejectsURL(url);
    List<JSONObject> bodies=new ArrayList<>();List<String> models=new ArrayList<>();Set<String> sentTitles=new HashSet<>();
    AiClient client=new AiClient((c,path,body)->{
      if(path.equals("/models"))return "{\"data\":[{\"id\":\"chosen-model\"},{\"id\":\"other-model\"}]}";
      check(c.model.equals("chosen-model")&&body.getString("model").equals("chosen-model"),"Every request remains pinned");models.add(c.model);bodies.add(body);
      check(!body.toString().contains(KEY)&&!body.toString().contains("PRIVATE_BODY_SENTINEL")&&!body.toString().contains("PRIVATE_HEAT_SENTINEL"),"No credentials/body/heat in title-only request");
      String user=body.getJSONArray("messages").getJSONObject(1).getString("content");
      if(user.startsWith("{")){JSONObject input=new JSONObject(user);if(input.has("titles")){JSONArray rows=input.getJSONArray("titles");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);check(row.length()==2&&row.has("title")&&row.has("id"),"Title payload has no source URL/date/body");sentTitles.add(row.getString("title"));}}}
      return reply(c.model,"按标题归纳的简短汇报");
    });
    AiClient.Config locked=config();check(client.models(locked).ids.size()==2&&locked.model.equals("chosen-model"),"Discovery cannot change model");client.probe(locked);
    List<Article> many=new ArrayList<>();for(int i=0;i<121;i++)many.add(article("item"+i));
    AiEngine.Result report=new AiEngine(client).analyze(locked,many,false,()->true);
    check(report.count==121&&sentTitles.size()==121&&report.model.equals(locked.model),"All titles analyzed across batches with fixed model");
    check(bodies.size()==4,"Probe plus two 96-title batches plus summary merge");
    AiBatchCache cache=new AiBatchCache();AiEngine cachedEngine=new AiEngine(client,cache);
    AiEngine.Result first=cachedEngine.analyze(locked,many,false,()->true);int before=bodies.size();
    AiEngine.Result again=cachedEngine.analyze(locked,many,false,()->true);
    check(first.requests==3&&again.requests==0&&again.reused==3&&bodies.size()==before,"Unchanged title batches and summary merge reuse success without paid calls");
    AiBatchCache reload=new AiBatchCache();reload.restore(cache.json());
    check(new AiEngine(client,reload).analyze(locked,many,false,()->true).requests==0,"Batch cache survives local result persistence");
    AiClient.Config another=new AiClient.Config(locked.url,KEY,locked.model,"another-profile",true);
    check(new AiEngine(client,cache).analyze(another,many,false,()->true).requests==3,"Changing profile cannot reuse old-account cache");
    List<Article> changed=new ArrayList<>(many);changed.set(0,article("changed-title"));
    check(cachedEngine.analyze(locked,changed,false,()->true).requests>0,"Changed title batches are reanalyzed");
    check(!cache.json().toString().contains(KEY)&&!cache.json().toString().contains("PRIVATE_BODY_SENTINEL"),"Persistent batch cache contains no credentials or request bodies");
    check(AiClient.error(new java.net.SocketTimeoutException(KEY)).contains("超时")&&!AiClient.error(new java.net.SocketTimeoutException(KEY)).contains(KEY),"Timeout classification is specific and never leaks exception text");
    check(AiClient.error(new java.net.UnknownHostException(KEY)).contains("域名")&&AiClient.error(new java.net.SocketException(KEY)).contains("中断"),"DNS and dropped connections have separate diagnostics");
    boolean malformed=false;try{new AiClient((c,path,body)->"<html>bad gateway</html>").probe(locked);}catch(AiClient.Failure failure){malformed=failure.getMessage().contains("响应格式");}check(malformed,"Non-compatible response diagnosed separately from network failure");
    System.out.println("PASS: AI endpoint safety, pinned model, title-only privacy and complete batched coverage");

    AiClient selection=new AiClient((c,path,body)->reply(c.model,"{\"groups\":[{\"title\":\"重点报道\",\"reason\":\"仅标题判断\",\"ids\":[\"r0\"]}]}"));
    AiEngine.Result curated=new AiEngine(selection).analyze(locked,Collections.singletonList(article("single")),true,()->true);
    check(curated.groups.size()==1&&curated.groups.get(0).reports.size()==1,"AI can select a single report");
    AiEngine.Result restored=AiEngine.Result.from(curated.json());check(restored.groups.get(0).reports.get(0).id.equals("single")&&!curated.json().toString().contains(KEY),"Local evidence roundtrip and no settings secret");
    String duplicates="{\"groups\":[{\"title\":\"事件一\",\"ids\":[\"r0\",\"r0\"]},{\"title\":\"事件二\",\"ids\":[\"r0\",\"r1\"]},{\"title\":\"重复事件\",\"ids\":[\"r1\"]}]}";
    List<int[]> progress=new ArrayList<>();
    AiEngine.Result deduped=new AiEngine(new AiClient((c,path,body)->reply(c.model,duplicates))).analyze(locked,Arrays.asList(article("a"),article("b")),true,()->true,(done,total)->progress.add(new int[]{done,total}));
    check(deduped.groups.size()==2&&deduped.groups.get(0).reports.size()==1&&deduped.groups.get(1).reports.get(0).id.equals("b"),"Within/across-group duplicates do not discard valid selection");
    check(progress.get(0)[0]==0&&progress.get(progress.size()-1)[0]==1,"Request progress reports actual completed calls");
    int[] repair={0};AiEngine.Result repaired=new AiEngine(new AiClient((c,path,body)->reply(c.model,++repair[0]==1?"{\"groups\":[{\"title\":\"wrong\",\"ids\":[\"r999\"]}]}":"{\"groups\":[{\"title\":\"fixed\",\"ids\":[\"r0\"]}]}"))).analyze(locked,Collections.singletonList(article("repair")),true,()->true);
    check(repair[0]==2&&repaired.groups.get(0).reports.get(0).id.equals("repair"),"Unknown evidence gets one bounded same-model repair, never an invented link");
    for(String output:new String[]{"{\"groups\":[{\"title\":\"bad\",\"ids\":[\"999\"]}]}"}){
      boolean rejected=false;try{new AiEngine(new AiClient((c,path,body)->reply(c.model,output))).analyze(locked,Collections.singletonList(article("one")),true,()->true);}catch(Exception e){rejected=true;}check(rejected,"Unknown/repeated evidence rejected");
    }
    AiBatchCache selectedCache=new AiBatchCache();int[] invalidCalls={0};
    AiClient sometimesInvalid=new AiClient((c,path,body)->reply(c.model,++invalidCalls[0]==1?"{\"groups\":[{\"title\":\"bad\",\"ids\":[\"r999\"]}]}":"{\"groups\":[{\"title\":\"good\",\"ids\":[\"r0\"]}]}"));
    new AiEngine(sometimesInvalid,selectedCache).analyze(locked,Collections.singletonList(article("validated")),true,()->true);
    AiEngine.Result validated=new AiEngine(sometimesInvalid,selectedCache).analyze(locked,Collections.singletonList(article("validated")),true,()->true);
    check(invalidCalls[0]==3&&validated.groups.size()==1,"Invalid selection must not poison persistent cache or bypass local evidence validation");
    int[] jsonCalls={0};new AiClient((c,path,body)->{jsonCalls[0]++;check(body.getJSONObject("thinking").getString("type").equals("disabled"),"Official DeepSeek analysis remains non-thinking");check(body.getJSONObject("response_format").getString("type").equals("json_object"),"DeepSeek selection requests supported JSON output");return reply(c.model,"{\"groups\":[]}");}).complete(locked,"输出JSON：{\"groups\":[]}","titles",1800);
    check(jsonCalls[0]==1,"JSON mode must not probe or change the model");
    int[] calls={0};boolean mismatch=false;try{new AiClient((c,path,body)->{calls[0]++;return reply("different-model","text");}).probe(locked);}catch(AiClient.Failure e){mismatch=true;}check(mismatch&&calls[0]==1,"Wrong returned model rejected without retry/fallback");
    boolean noInventedModels=false;try{new AiClient((c,path,body)->{throw new AiClient.Failure("HTTP 404");}).models(new AiClient.Config("https://vendor.example:8443/custom/v1",KEY,"","p",true));}catch(AiClient.Failure error){noInventedModels=error.getMessage().contains("手填");}check(noInventedModels,"Unsupported model lists use explicit manual IDs, never brand-specific guesses");
    check(AiClient.baseURL("https://vendor.example:8443/custom/v1/chat/completions").equals("https://vendor.example:8443/custom/v1"),"Public HTTPS custom API ports and paths are preserved");
    boolean cancelled=false;try{new AiEngine(client).analyze(locked,many,false,()->false);}catch(AiClient.Failure e){cancelled=true;}check(cancelled,"Cancelled profile does not send another request");
    System.out.println("PASS: AI single-report curation, evidence validation, stale-profile cancellation and no model fallback");

    NewsEngine engine=new NewsEngine(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0]).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    Snapshot infoq=new Snapshot("infoq-cn");Article ai=article("ai");Article general=article("general");general.title="Java database architecture";infoq.items.add(ai);infoq.items.add(general);infoq.fetched=1;
    Map<String,Snapshot> snapshots=new HashMap<>();snapshots.put(infoq.source,infoq);Map<String,Boolean> enabled=new HashMap<>();enabled.put(infoq.source,true);
    check(AiEngine.section(AiEngine.NEWS,engine,catalog,snapshots,enabled).size()==0&&AiEngine.section(AiEngine.AI,engine,catalog,snapshots,enabled).size()==2,"Analysis follows actual news/AI partition");
    infoq.error="offline";check(AiEngine.section(AiEngine.AI,engine,catalog,snapshots,enabled).isEmpty(),"Failed-source old items are not called a fresh update report");
    check(AiEngine.foreign("English release news")&&AiEngine.foreign("新しい技術が登場")&&AiEngine.foreign("새로운 기술")&&!AiEngine.foreign("中文 AI 新闻")&&!AiEngine.foreign("123"),"Foreign-preview heuristic includes Japanese and Korean");
    String translated=new AiEngine(new AiClient((c,path,body)->reply(c.model,"人工翻译测试译文"))).translate(locked,"English article preview",()->true);check(translated.contains("译文"),"Manual preview translation uses the locked client");
    Article foreignTitle=article("foreign-label");foreignTitle.title="New database engine";String originalTitle=foreignTitle.title;
    AiClient titleClient=new AiClient((c,path,body)->reply(c.model,"{\"titles\":[{\"id\":\"r0\",\"text\":\"新数据库引擎\"}]}"));
    Map<Article,String> labels=AiTitles.translate(titleClient,locked,Collections.singletonList(foreignTitle),()->true);AiTitles titleCache=new AiTitles();titleCache.put(foreignTitle,labels.get(foreignTitle));
    check(titleCache.get(foreignTitle).equals("新数据库引擎")&&foreignTitle.title.equals(originalTitle),"Translated list label never mutates original article");AiTitles titleReload=new AiTitles();titleReload.restore(titleCache.json());check(titleReload.get(foreignTitle).equals("新数据库引擎"),"Translated titles survive persistence");
    Article dated=article("content-date");dated.published=java.time.Instant.parse("2026-11-10T08:00:00Z").toEpochMilli();dated.fetched=java.time.Instant.parse("2026-11-12T08:00:00Z").toEpochMilli();Snapshot datedSnapshot=new Snapshot(dated.source);datedSnapshot.items.add(dated);NewsState datedState=new NewsState();datedState.snapshots.put(dated.source,datedSnapshot);datedState.favorites.add(dated);datedState.read.add(dated.id);titleCache.put(dated,"日期缓存");
    check(ContentDates.counts(Collections.singletonList(dated)).containsKey("2026-11-10")&&!ContentDates.counts(Collections.singletonList(dated)).containsKey("2026-11-12"),"Cleanup dates use publication, not acquisition");ContentDates.clearSnapshots(datedState.snapshots.values(),day->day.equals("2026-11-10"));titleCache.delete(day->day.equals("2026-11-10"));check(datedSnapshot.items.isEmpty()&&datedState.favorites.size()==1&&datedState.read.contains(dated.id)&&titleCache.get(dated)==null,"Date cleanup removes linked cache but preserves favorites/read");
    check(ContentDates.older("2026-11-10").equals(Arrays.asList("2026-11-09","2026-11-08","2026-11-07")),"Each earlier click expands exactly three calendar days");
    JSONArray conversation=new JSONArray().put(new JSONObject().put("role","user").put("content","你好"));String chat=new AiClient((c,path,body)->{check(body.getJSONArray("messages").getJSONObject(0).getString("role").equals("user")&&body.getString("model").equals(locked.model),"Direct chat uses native messages and pinned model");return reply(c.model,"你好，用户");}).chat(locked,conversation,100);check(chat.contains("用户"),"Direct conversation response parsed");
    System.out.println("PASS: AI scope isolation, failed-source exclusion and manual foreign-text translation");
  }
}
