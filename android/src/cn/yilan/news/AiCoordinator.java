package cn.yilan.news;

import android.os.Handler;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import cn.yilan.news.NewsEngine.*;

/** Main-thread state, serial analysis worker, immutable per-run model/profile. */
public final class AiCoordinator {
  public final AiClient client;
  public final ExecutorService tests=Executors.newFixedThreadPool(2);
  private final ExecutorService analysis=Executors.newSingleThreadExecutor(),storage;
  private final Handler handler;private final AiVault vault;private final Runnable changed;
  public volatile AiClient.Config config;
  private volatile long generation;
  public boolean running,loading,saving;public String loadError="";
  public final Map<String,AiEngine.Result> results=new LinkedHashMap<>();
  public final Map<String,String> errors=new LinkedHashMap<>();
  public AiCoordinator(AiVault vault,Handler handler,ExecutorService storage,Runnable changed){this(vault,handler,storage,changed,new AiClient());}
  public AiCoordinator(AiVault vault,Handler handler,ExecutorService storage,Runnable changed,AiClient client){this.vault=vault;this.handler=handler;this.storage=storage;this.changed=changed;this.client=client;}
  public boolean enabled(){return config!=null&&config.enabled&&!config.model.isEmpty();}
  public void load(){
    loading=true;final long token=generation;storage.execute(()->{
      AiClient.Config loaded=null;Map<String,AiEngine.Result> rows=new LinkedHashMap<>();Map<String,String> failures=new LinkedHashMap<>();String error="";
      try{loaded=vault.load();JSONObject data=vault.loadResults();JSONObject saved=data.optJSONObject("results"),failed=data.optJSONObject("errors");
        if(saved!=null)for(Iterator<String> it=saved.keys();it.hasNext();){String scope=it.next();try{AiEngine.Result r=AiEngine.Result.from(saved.getJSONObject(scope));if(loaded!=null&&r.profile.equals(loaded.id))rows.put(scope,r);}catch(Exception ignored){}}
        if(failed!=null)for(Iterator<String> it=failed.keys();it.hasNext();){String scope=it.next();failures.put(scope,failed.optString(scope));}
      }catch(Exception e){error=loaded==null?"AI 配置读取失败，请重新设置；不会降级明文保存。":"AI 结果缓存读取失败，锁定模型已保留；请重新分析。";}
      final AiClient.Config restored=loaded;final String reason=error;handler.post(()->{if(token!=generation)return;config=restored;results.putAll(rows);errors.putAll(failures);loadError=reason;loading=false;changed.run();});
    });
  }
  public void save(AiClient.Config next,Consumer<String> completion){
    if(saving||loading){completion.accept("AI 配置正在读写，请稍候");return;}final long token=++generation;running=false;saving=true;changed.run();
    storage.execute(()->{try{vault.save(next);handler.post(()->{if(token!=generation)return;if(config==null||!config.id.equals(next.id)){results.clear();errors.clear();}config=next;saving=false;loadError="";persist();changed.run();completion.accept("");});}
      catch(Exception e){handler.post(()->{if(token==generation){saving=false;changed.run();completion.accept("AI 配置加密保存失败，原配置保留");}});}});
  }
  public void forget(){generation++;running=false;config=null;results.clear();errors.clear();vault.forget();changed.run();}
  private void persist(){
    try{JSONObject rows=new JSONObject(),failures=new JSONObject();for(Map.Entry<String,AiEngine.Result> e:results.entrySet())rows.put(e.getKey(),e.getValue().json());for(Map.Entry<String,String> e:errors.entrySet())failures.put(e.getKey(),e.getValue());JSONObject data=new JSONObject().put("results",rows).put("errors",failures);
      long revision=vault.revision();storage.execute(()->{try{vault.saveResults(data,revision);}catch(Exception e){handler.post(()->{loadError="AI 结果保存失败，当前页面仍可阅读。";changed.run();});}});
    }catch(Exception e){loadError="AI 结果无法序列化，旧文件保留。";}
  }
  public void run(NewsEngine engine,JSONObject catalog,Map<String,Snapshot> snapshots,Map<String,Boolean> enabled,List<Article> archive){
    if(!enabled()||running||saving||loading)return;
    LinkedHashMap<String,List<Article>> jobs=new LinkedHashMap<>();
    for(String scope:new String[]{AiEngine.NEWS,AiEngine.HOT,AiEngine.AI})jobs.put(scope,AiEngine.section(scope,engine,catalog,snapshots,enabled));
    String since=FeaturedNews.initialDay(System.currentTimeMillis());Set<String> boards=new HashSet<>();for(String id:NewsEngine.HOT_SOURCES)if(Boolean.TRUE.equals(enabled.get(id))&&ReadingRules.aiBoard(id,snapshots.get(id)))boards.add(id);
    jobs.put(AiEngine.FEATURED_NEWS,AiEngine.featuredInput(false,archive,engine,enabled,boards,since,""));jobs.put(AiEngine.FEATURED_AI,AiEngine.featuredInput(true,archive,engine,enabled,boards,since,""));
    execute(jobs,since,false);
  }
  public boolean older(boolean ai,NewsEngine engine,Map<String,Boolean> enabled,Map<String,Snapshot> snapshots,List<Article> archive){
    if(!enabled()||running)return false;String scope=ai?AiEngine.FEATURED_AI:AiEngine.FEATURED_NEWS;AiEngine.Result old=results.get(scope);
    if(old==null||old.since.isEmpty()||old.groups.size()>=160)return false;
    String before=old.since;String latest=null;for(Article a:archive)if(a.published>0){String day=FeaturedNews.day(a.published);if(day.compareTo(before)<0&&(latest==null||day.compareTo(latest)>0))latest=day;}
    if(latest==null)return false;String since=LocalDate.parse(latest).minusDays(6).toString();Set<String> boards=new HashSet<>();for(String id:NewsEngine.HOT_SOURCES)if(Boolean.TRUE.equals(enabled.get(id))&&ReadingRules.aiBoard(id,snapshots.get(id)))boards.add(id);
    List<Article> input=AiEngine.featuredInput(ai,archive,engine,enabled,boards,since,before);if(input.isEmpty())return false;
    LinkedHashMap<String,List<Article>> jobs=new LinkedHashMap<>();jobs.put(scope,input);execute(jobs,since,true);return true;
  }
  private void execute(LinkedHashMap<String,List<Article>> jobs,String since,boolean append){
    final AiClient.Config locked=config;final long token=++generation;running=true;changed.run();
    analysis.execute(()->{
      AiEngine engine=new AiEngine(client);
      String terminal=null;for(Map.Entry<String,List<Article>> job:jobs.entrySet()){
        if(token!=generation)return;String scope=job.getKey();
        if(terminal!=null){final String reason=terminal;handler.post(()->{if(token==generation){errors.put(scope,reason);persist();}});continue;}
        try{AiEngine.Result result=engine.analyze(locked,job.getValue(),scope.startsWith("featured-"),()->token==generation);result.since=since;
          handler.post(()->{if(token!=generation)return;
            if(append&&results.containsKey(scope)){AiEngine.Result previous=results.get(scope);List<AiEngine.Group> merged=new ArrayList<>(previous.groups);merged.addAll(result.groups);if(merged.size()>160){errors.put(scope,"AI 精选已达本地160组上限，旧结果保留");return;}result.groups.clear();result.groups.addAll(merged);}
            results.put(scope,result);errors.remove(scope);persist();
          });
        }catch(Exception e){String reason=AiClient.error(e);handler.post(()->{if(token==generation){errors.put(scope,reason);persist();}});if(e instanceof AiClient.Failure&&Arrays.asList(401,402,403,404,429).contains(((AiClient.Failure)e).status))terminal=reason;}
      }
      handler.post(()->{if(token==generation){running=false;changed.run();}});
    });
  }
  public void translate(String text,Consumer<String> success,Consumer<String> failure){
    if(!enabled()){failure.accept("请先在设置中启用 AI 并验证模型");return;}
    final AiClient.Config locked=config;final long token=generation;
    tests.execute(()->{try{String result=new AiEngine(client).translate(locked,text,()->config!=null&&config.enabled&&config.id.equals(locked.id));handler.post(()->{if(config!=null&&config.id.equals(locked.id)&&config.enabled)success.accept(result);else failure.accept("AI 配置已改变，未采用旧模型的译文");});}catch(Exception e){String reason=AiClient.error(e);handler.post(()->failure.accept(reason));}});
  }
  public void close(){generation++;analysis.shutdownNow();tests.shutdownNow();}
}
