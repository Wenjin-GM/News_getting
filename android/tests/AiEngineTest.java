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
    for(String url:new String[]{"http://api.deepseek.com","https://localhost/v1","https://127.0.0.1/v1","https://user:secret@example.com","https://example.com/v1?api_key=secret","https://example.com:8080/v1","file:///private"})rejectsURL(url);
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
    check(bodies.size()==5,"Probe plus three title batches plus summary merge");
    System.out.println("PASS: AI endpoint safety, pinned model, title-only privacy and complete batched coverage");

    AiClient selection=new AiClient((c,path,body)->reply(c.model,"{\"groups\":[{\"title\":\"重点报道\",\"reason\":\"仅标题判断\",\"ids\":[\"0\"]}]}"));
    AiEngine.Result curated=new AiEngine(selection).analyze(locked,Collections.singletonList(article("single")),true,()->true);
    check(curated.groups.size()==1&&curated.groups.get(0).reports.size()==1,"AI can select a single report");
    AiEngine.Result restored=AiEngine.Result.from(curated.json());check(restored.groups.get(0).reports.get(0).id.equals("single")&&!curated.json().toString().contains(KEY),"Local evidence roundtrip and no settings secret");
    for(String output:new String[]{"{\"groups\":[{\"title\":\"bad\",\"ids\":[\"999\"]}]}","{\"groups\":[{\"title\":\"bad\",\"ids\":[\"0\",\"0\"]}]}"}){
      boolean rejected=false;try{new AiEngine(new AiClient((c,path,body)->reply(c.model,output))).analyze(locked,Collections.singletonList(article("one")),true,()->true);}catch(Exception e){rejected=true;}check(rejected,"Unknown/repeated evidence rejected");
    }
    int[] calls={0};boolean mismatch=false;try{new AiClient((c,path,body)->{calls[0]++;return reply("different-model","text");}).probe(locked);}catch(AiClient.Failure e){mismatch=true;}check(mismatch&&calls[0]==1,"Wrong returned model rejected without retry/fallback");
    AiClient.Models glm=new AiClient((c,path,body)->{throw new AiClient.Failure("HTTP 404");}).models(new AiClient.Config("https://open.bigmodel.cn/api/paas/v4",KEY,"","p",true));
    check(!glm.fromServer&&!glm.ids.isEmpty(),"GLM catalog candidates are never labelled key-verified");
    boolean cancelled=false;try{new AiEngine(client).analyze(locked,many,false,()->false);}catch(AiClient.Failure e){cancelled=true;}check(cancelled,"Cancelled profile does not send another request");
    System.out.println("PASS: AI single-report curation, evidence validation, stale-profile cancellation and no model fallback");

    NewsEngine engine=new NewsEngine(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0]).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    Snapshot infoq=new Snapshot("infoq-cn");Article ai=article("ai");Article general=article("general");general.title="Java database architecture";infoq.items.add(ai);infoq.items.add(general);infoq.fetched=1;
    Map<String,Snapshot> snapshots=new HashMap<>();snapshots.put(infoq.source,infoq);Map<String,Boolean> enabled=new HashMap<>();enabled.put(infoq.source,true);
    check(AiEngine.section(AiEngine.NEWS,engine,catalog,snapshots,enabled).size()==1&&AiEngine.section(AiEngine.AI,engine,catalog,snapshots,enabled).size()==1,"Analysis follows actual news/AI partition");
    infoq.error="offline";check(AiEngine.section(AiEngine.AI,engine,catalog,snapshots,enabled).isEmpty(),"Failed-source old items are not called a fresh update report");
    check(AiEngine.foreign("English release news")&&AiEngine.foreign("新しい技術が登場")&&AiEngine.foreign("새로운 기술")&&!AiEngine.foreign("中文 AI 新闻")&&!AiEngine.foreign("123"),"Foreign-preview heuristic includes Japanese and Korean");
    String translated=new AiEngine(new AiClient((c,path,body)->reply(c.model,"人工翻译测试译文"))).translate(locked,"English article preview",()->true);check(translated.contains("译文"),"Manual preview translation uses the locked client");
    System.out.println("PASS: AI scope isolation, failed-source exclusion and manual foreign-text translation");
  }
}
