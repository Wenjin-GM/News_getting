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
  private final ExecutorService analysis=Executors.newSingleThreadExecutor(),workers=Executors.newFixedThreadPool(2),storage;
  private final Handler handler;private final AiVault vault;private final Runnable changed;
  public volatile AiClient.Config config;
  private volatile long generation;
  public boolean running,loading,saving,resultsBlocked;public String loadError="",saveError="";private long saveSequence;
  private final Map<String,String> durable=new HashMap<>();public final Set<String> unavailable=new LinkedHashSet<>();
  public String stage="",detail="";public int stageDone,stageTotal=1;
  public final Map<String,AiEngine.Result> results=new LinkedHashMap<>();
  public final AiBatchCache batchCache=new AiBatchCache();
  public final AiTitles titles=new AiTitles();private final Set<String> titleAttempts=new HashSet<>();private List<Article> visibleTitles=new ArrayList<>();
  public void visibleTitles(Collection<Article> rows){visibleTitles=new ArrayList<>(rows);}
  public final Map<String,String> statuses=new LinkedHashMap<>();
  public final Map<String,String> errors=new LinkedHashMap<>();
  public AiCoordinator(AiVault vault,Handler handler,ExecutorService storage,Runnable changed){this(vault,handler,storage,changed,new AiClient());}
  public AiCoordinator(AiVault vault,Handler handler,ExecutorService storage,Runnable changed,AiClient client){this.vault=vault;this.handler=handler;this.storage=storage;this.changed=changed;this.client=client;}
  public boolean enabled(){return config!=null&&config.enabled&&!config.model.isEmpty();}
  public void load(){
    loading=true;final long token=generation;storage.execute(()->{
      AiClient.Config loaded=null;Map<String,AiEngine.Result> rows=new LinkedHashMap<>();Map<String,String> failures=new LinkedHashMap<>();Set<String> checked=new LinkedHashSet<>();String error="";JSONObject cached=null,translated=null;boolean blocked=false;
      try{loaded=vault.load();JSONObject data=vault.loadResults();if(loaded!=null&&loaded.id.equals(data.optString("cacheProfile"))){cached=data.optJSONObject("batches");translated=data.optJSONObject("titles");}JSONObject saved=data.optJSONObject("results"),failed=data.optJSONObject("errors");
        if(saved!=null)for(Iterator<String> it=saved.keys();it.hasNext();){String scope=it.next();try{AiEngine.Result r=AiEngine.Result.from(saved.getJSONObject(scope));if(loaded!=null&&(r.profile.equals(loaded.id)||!ContentDates.date(scope).isEmpty()))rows.put(scope,r);}catch(Exception ignored){}}
        JSONArray checks=data.optJSONArray("historyChecks");for(int i=0;checks!=null&&i<checks.length();i++){String key=checks.optString(i);if(!ContentDates.date(key).isEmpty())checked.add(key);}
        if(failed!=null)for(Iterator<String> it=failed.keys();it.hasNext();){String scope=it.next();failures.put(scope,failed.optString(scope));}
      }catch(Exception e){blocked=true;error=loaded==null?"AI 配置读取失败，请重新设置；旧文件受保护。":"AI 结果读取失败，原文件保留，已暂停自动分析和写入；请重试读取。";}
      final boolean protectedFiles=blocked;final JSONObject translatedData=translated;final JSONObject cacheData=cached;final AiClient.Config restored=loaded;final String reason=error;
      handler.post(()->{if(token!=generation)return;if(restored!=null||!protectedFiles)config=restored;resultsBlocked=protectedFiles;loadError=reason;loading=false;
        if(!protectedFiles){batchCache.restore(cacheData);titles.restore(translatedData);results.clear();results.putAll(rows);unavailable.clear();unavailable.addAll(checked);durable.clear();for(String key:rows.keySet())try{durable.put(key,rows.get(key).json().toString());}catch(Exception ignored){}
          boolean migrated=AiHistory.migrate(results);String today=FeaturedNews.day(System.currentTimeMillis());for(String scope:new String[]{AiEngine.FEATURED_NEWS,AiEngine.FEATURED_AI})if(daily(scope,today)!=null){results.put(scope,daily(scope,today));String signature=durable.get(ContentDates.key(scope,today));if(signature!=null)durable.put(scope,signature);}
          errors.clear();errors.putAll(failures);saveError="";if(migrated)persist();
        }changed.run();});
    });
  }
  public void save(AiClient.Config next,Consumer<String> completion){
    if(saving||loading){completion.accept("AI 配置正在读写，请稍候");return;}final long token=++generation;running=false;saving=true;changed.run();
    storage.execute(()->{try{vault.save(next);handler.post(()->{if(token!=generation)return;if(config==null||!config.id.equals(next.id)){results.entrySet().removeIf(row->ContentDates.date(row.getKey()).isEmpty());errors.clear();batchCache.clear();titles.clear();titleAttempts.clear();}statuses.clear();config=next;saving=false;if(!resultsBlocked)loadError="";persist();changed.run();completion.accept("");});}
      catch(Exception e){handler.post(()->{if(token==generation){saving=false;changed.run();completion.accept("AI 配置加密保存失败，原配置保留");}});}});
  }
  public void forget(){generation++;saveSequence++;running=false;config=null;results.clear();errors.clear();statuses.clear();durable.clear();unavailable.clear();resultsBlocked=false;loadError=saveError="";batchCache.clear();titles.clear();titleAttempts.clear();vault.forget();changed.run();}
  public String savedState(String key){try{return results.get(key)!=null&&results.get(key).json().toString().equals(durable.get(key))?"已保存到本机":saveError.isEmpty()?"正在保存到本机":"未保存到本机；请重试保存";}catch(Exception e){return "未保存到本机";}}
  public void retrySave(){persist();}
  public void noContent(String scope,Collection<String> days){for(String day:days){String key=ContentDates.key(scope,day);if(!results.containsKey(key)){unavailable.add(key);errors.remove(key);}}persist();changed.run();}
  public void historyFailure(String scope,Collection<String> days){for(String day:days)errors.put(ContentDates.key(scope,day),"来源获取失败，未重新选择；已有精选保留");persist();changed.run();}
  private void persist(){
    if(resultsBlocked)return;final long sequence=++saveSequence;final long revision=vault.revision();
    try{JSONObject rows=new JSONObject(),failures=new JSONObject();Map<String,String> signatures=new HashMap<>();for(Map.Entry<String,AiEngine.Result> e:results.entrySet()){JSONObject row=e.getValue().json();rows.put(e.getKey(),row);signatures.put(e.getKey(),row.toString());}for(Map.Entry<String,String> e:errors.entrySet())failures.put(e.getKey(),e.getValue());JSONObject data=new JSONObject().put("results",rows).put("errors",failures).put("historyChecks",new JSONArray(unavailable)).put("cacheProfile",config==null?"":config.id).put("batches",batchCache.json()).put("titles",titles.json());
      storage.execute(()->{try{vault.saveResults(data,revision);handler.post(()->{if(sequence!=saveSequence||revision!=vault.revision())return;durable.clear();durable.putAll(signatures);saveError="";changed.run();});}catch(Exception e){handler.post(()->{if(sequence!=saveSequence||revision!=vault.revision())return;saveError="AI 结果保存失败：请检查剩余空间或清理缓存；旧文件保留。";changed.run();});}});
    }catch(Exception e){saveError="AI 结果无法序列化，旧文件保留。";}
  }
  public void run(NewsEngine engine,JSONObject catalog,Map<String,Snapshot> snapshots,Map<String,Boolean> enabled,List<Article> archive){
    run(engine,catalog,snapshots,enabled,archive,"");
  }
  public void run(NewsEngine engine,JSONObject catalog,Map<String,Snapshot> snapshots,Map<String,Boolean> enabled,List<Article> archive,String only){
    if(!enabled()||running||saving||loading||resultsBlocked)return;
    LinkedHashMap<String,List<Article>> jobs=new LinkedHashMap<>();
    for(String scope:new String[]{AiEngine.NEWS,AiEngine.HOT,AiEngine.AI})jobs.put(scope,AiEngine.section(scope,engine,catalog,snapshots,enabled));
    String since=FeaturedNews.day(System.currentTimeMillis());Set<String> boards=new HashSet<>();for(String id:NewsEngine.HOT_SOURCES)if(Boolean.TRUE.equals(enabled.get(id))&&ReadingRules.aiBoard(id,snapshots.get(id)))boards.add(id);
    String before=LocalDate.parse(since).plusDays(1).toString();
    List<Article> records=AiHistory.local(archive,snapshots,results);jobs.put(ContentDates.key(AiEngine.FEATURED_NEWS,since),AiEngine.featuredInput(false,records,engine,enabled,boards,since,before));jobs.put(ContentDates.key(AiEngine.FEATURED_AI,since),AiEngine.featuredInput(true,records,engine,enabled,boards,since,before));
    if(!only.isEmpty())jobs.entrySet().removeIf(row->!ContentDates.scope(row.getKey()).equals(only));
    List<Article> labels=new ArrayList<>(visibleTitles);titleAttempts.clear();List<Article> missing=titles.missing(labels);if(!missing.isEmpty()){for(Article a:missing)titleAttempts.add(a.id+"|"+a.title);jobs.put("titles",missing);}
    if(!jobs.isEmpty())execute(jobs,since,false);
  }
  public AiEngine.Result daily(String scope,String day){return results.get(ContentDates.key(scope,day));}
  public void selectDates(boolean ai,List<String> days,NewsEngine engine,Map<String,Boolean> enabled,Map<String,Snapshot> snapshots,List<Article> archive,boolean force){
    selectDates(ai,days,engine,enabled,snapshots,archive,force,()->{});
  }
  public void selectDates(boolean ai,List<String> days,NewsEngine engine,Map<String,Boolean> enabled,Map<String,Snapshot> snapshots,List<Article> archive,boolean force,Runnable complete){
    if(!enabled()||running||loading||saving||resultsBlocked)return;String scope=ai?AiEngine.FEATURED_AI:AiEngine.FEATURED_NEWS;Set<String> boards=new HashSet<>();for(String id:NewsEngine.HOT_SOURCES)if(Boolean.TRUE.equals(enabled.get(id))&&ReadingRules.aiBoard(id,snapshots.get(id)))boards.add(id);
    List<Article> records=AiHistory.local(archive,snapshots,results);LinkedHashMap<String,List<Article>> jobs=new LinkedHashMap<>();for(String day:days){if(!force&&daily(scope,day)!=null)continue;List<Article> input=AiEngine.featuredInput(ai,records,engine,enabled,boards,day,LocalDate.parse(day).plusDays(1).toString());if(!input.isEmpty())jobs.put(ContentDates.key(scope,day),input);}
    if(force)batchCache.clear();if(!jobs.isEmpty())execute(jobs,"",false,complete);else{changed.run();complete.run();}
  }
  public void ensureTitles(Collection<Article> articles){
    if(!enabled()||running||loading||saving||resultsBlocked)return;List<Article> missing=titles.missing(articles);missing.removeIf(a->titleAttempts.contains(a.id+"|"+a.title));if(missing.isEmpty())return;for(Article a:missing)titleAttempts.add(a.id+"|"+a.title);
    LinkedHashMap<String,List<Article>> jobs=new LinkedHashMap<>();jobs.put("titles",missing);execute(jobs,"",false);
  }
  public void deleteDates(java.util.function.Predicate<String> dates){
    generation++;running=false;statuses.clear();stage=detail="";vault.invalidatePendingResults();batchCache.clear();titles.delete(dates);titleAttempts.clear();
    for(String key:new ArrayList<>(results.keySet())){AiEngine.Result r=results.get(key);String day=ContentDates.date(key);
      if(!day.isEmpty()){if(dates.test(day))results.remove(key);continue;}
      if(key.startsWith("featured-")||r.days.isEmpty()||r.days.stream().anyMatch(dates)){results.remove(key);errors.remove(key);}
    }
    errors.keySet().removeIf(key->!ContentDates.date(key).isEmpty()&&dates.test(ContentDates.date(key)));unavailable.removeIf(key->dates.test(ContentDates.date(key)));persist();changed.run();
  }
  private void execute(LinkedHashMap<String,List<Article>> jobs,String since,boolean append){
    execute(jobs,since,append,()->{});
  }
  private void execute(LinkedHashMap<String,List<Article>> jobs,String since,boolean append,Runnable complete){
    final AiClient.Config locked=config;final long token=++generation;running=true;stage=jobs.containsKey("titles")?"翻译标题":jobs.keySet().iterator().next().startsWith("featured-")?"生成精选":"生成汇报";stageDone=0;stageTotal=1;
    statuses.clear();for(String scope:jobs.keySet())statuses.put(scope,scope.startsWith("featured-")?"等待生成精选":"等待生成汇报");changed.run();
    analysis.execute(()->{
      java.util.concurrent.atomic.AtomicReference<String> terminal=new java.util.concurrent.atomic.AtomicReference<>();
      try{
        if(jobs.containsKey("titles")){
          List<Article> missing=titles.missing(jobs.get("titles"));handler.post(()->{if(token==generation){stage="翻译标题";stageDone=0;stageTotal=Math.max(1,missing.size());statuses.put("titles","正在翻译标题");changed.run();}});
          try{Map<Article,String> labels=AiTitles.translate(client,locked,missing,()->token==generation,(done,total)->handler.post(()->{if(token==generation){stageDone=done;stageTotal=Math.max(1,total);detail="标题 "+done+"/"+total;changed.run();}}),block->handler.post(()->{if(token==generation)try{for(Map.Entry<Article,String> row:block.entrySet())titles.put(row.getKey(),row.getValue());persist();changed.run();}catch(Exception ignored){}}));handler.post(()->{if(token==generation){try{for(Map.Entry<Article,String> row:labels.entrySet())titles.put(row.getKey(),row.getValue());errors.remove("titles");statuses.remove("titles");persist();changed.run();}catch(Exception e){errors.put("titles",AiClient.error(e));}}});}
          catch(Exception e){String error=AiClient.error(e);handler.post(()->{if(token==generation){errors.put("titles",error);statuses.remove("titles");changed.run();}});}
        }
        for(boolean selection:new boolean[]{false,true}){
        List<Map.Entry<String,List<Article>>> phase=new ArrayList<>();for(Map.Entry<String,List<Article>> job:jobs.entrySet())if(!job.getKey().equals("titles")&&job.getKey().startsWith("featured-")==selection)phase.add(job);
        if(phase.isEmpty())continue;if(token!=generation)return;
        final String label=selection?"生成精选":"生成汇报";final int[] units=new int[phase.size()];final int phaseTotal=phase.size()*1000;
        handler.post(()->{if(token==generation){stage=label;stageDone=0;stageTotal=phaseTotal;changed.run();}});
        List<Future<?>> pending=new ArrayList<>();
        for(int index=0;index<phase.size();index++){
          final int position=index;final Map.Entry<String,List<Article>> job=phase.get(index);final String scope=job.getKey();
          pending.add(workers.submit(()->{
            if(token!=generation)return;
            final String name=scope.equals(AiEngine.NEWS)?"新闻最新":scope.equals(AiEngine.HOT)?"分站热榜":scope.equals(AiEngine.AI)?"平台动态":ContentDates.scope(scope).equals(AiEngine.FEATURED_NEWS)?"新闻精选":"AI 精选";
            handler.post(()->{if(token==generation){statuses.put(scope,"正在"+label);detail=name;changed.run();}});
            try{
              if(terminal.get()!=null)throw new AiClient.Failure(terminal.get());
              AiEngine.Result result=new AiEngine(client,batchCache).analyze(locked,job.getValue(),selection,()->token==generation&&terminal.get()==null,(done,total)->handler.post(()->{if(token==generation){units[position]=total==0?0:999*done/total;stageDone=0;for(int value:units)stageDone+=value;detail=name+" · 批次 "+done+"/"+total;changed.run();}}));result.since=ContentDates.date(scope).isEmpty()?since:ContentDates.date(scope);
              handler.post(()->{if(token!=generation)return;
                if(append&&results.containsKey(scope)){List<AiEngine.Group> merged=new ArrayList<>(results.get(scope).groups);merged.addAll(result.groups);if(merged.size()>160){errors.put(scope,"AI 精选已达本地160组上限，旧结果保留");statuses.remove(scope);changed.run();return;}result.groups.clear();result.groups.addAll(merged);}
                results.put(scope,result);unavailable.remove(scope);if(!ContentDates.date(scope).isEmpty()&&ContentDates.date(scope).equals(FeaturedNews.day(System.currentTimeMillis())))results.put(ContentDates.scope(scope),result);errors.remove(scope);statuses.remove(scope);units[position]=1000;stageDone=0;for(int value:units)stageDone+=value;persist();changed.run();
              });
            }catch(Exception e){String reason=terminal.get()!=null?terminal.get():AiClient.error(e);
              if(e instanceof AiClient.Failure&&Arrays.asList(401,402,403,404,429).contains(((AiClient.Failure)e).status))terminal.compareAndSet(null,reason);
              final String failure=reason;handler.post(()->{if(token==generation){errors.put(scope,failure);statuses.remove(scope);units[position]=1000;stageDone=0;for(int value:units)stageDone+=value;persist();changed.run();}});
            }
          }));
        }
        // Stage barrier: featured requests cannot start until every report has settled.
        for(Future<?> request:pending)request.get();
      }}catch(Exception e){handler.post(()->{if(token==generation){for(String scope:new ArrayList<>(statuses.keySet()))errors.put(scope,"AI 任务中断，旧结果保留");}});}
      finally{handler.post(()->{if(token==generation){running=false;statuses.clear();stage="";detail="";persist();changed.run();complete.run();}});}
    });
  }
  public void translate(String text,Consumer<String> success,Consumer<String> failure){
    if(!enabled()){failure.accept("请先在设置中启用 AI 并验证模型");return;}
    final AiClient.Config locked=config;final long token=generation;
    tests.execute(()->{try{String result=new AiEngine(client).translate(locked,text,()->config!=null&&config.enabled&&config.id.equals(locked.id));handler.post(()->{if(config!=null&&config.id.equals(locked.id)&&config.enabled)success.accept(result);else failure.accept("AI 配置已改变，未采用旧模型的译文");});}catch(Exception e){String reason=AiClient.error(e);handler.post(()->failure.accept(reason));}});
  }
  public void close(){generation++;analysis.shutdownNow();workers.shutdownNow();tests.shutdownNow();}
}
