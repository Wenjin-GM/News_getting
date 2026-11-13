package cn.yilan.news.test;

import android.app.Instrumentation;
import android.os.*;
import cn.yilan.news.*;
import cn.yilan.news.NewsEngine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real Android AtomicFile/Keystore persistence, isolated synthetic data only. */
public final class HistorySmoke extends Instrumentation {
  private String mode;private String checkpoint="start";
  @Override public void onCreate(Bundle args){super.onCreate(args);mode=args==null?"verify":args.getString("mode","verify");start();}
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static Article article(String day){Article a=new Article();a.id="history-"+day;a.source="chinanews";a.sourceName="中国新闻网";a.url="https://www.chinanews.com.cn/"+a.id;a.title="合成历史新闻";a.published=java.time.LocalDate.parse(day).atTime(12,0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();return a;}
  private static AiEngine.Result result(String day){AiEngine.Result r=new AiEngine.Result();r.profile="history-upgrade";r.model="history-model";r.since=day;r.at=1;r.count=1;AiEngine.Group group=new AiEngine.Group();group.title="合成已选";group.reports.add(article(day));r.groups.add(group);return r;}
  private void waitUntil(java.util.function.BooleanSupplier condition)throws Exception{long end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){boolean[] ready={false};runOnMainSync(()->ready[0]=condition.getAsBoolean());if(ready[0])return;SystemClock.sleep(20);}throw new AssertionError("Timed out at "+checkpoint);}
  @Override public void onStart(){Bundle out=new Bundle();ExecutorService disk=Executors.newSingleThreadExecutor();AiCoordinator[] coordinator={null};
    try{
      File root=getTargetContext().getFilesDir();AiVault vault=new AiVault(root);String scope=AiEngine.FEATURED_NEWS,key=ContentDates.key(scope,"2026-11-08");
      if("seed".equals(mode)){
        vault.forget();vault.save(new AiClient.Config("https://example.com/v1","synthetic-test-only","history-model","history-upgrade",false));
        AiEngine.Result old=result("2026-11-09");old.groups.addAll(result("2026-11-08").groups);AiEngine.Result empty=result("2026-11-07");empty.groups.clear();empty.count=0;
        JSONObject rows=new JSONObject().put(scope,old.json()).put(ContentDates.key(scope,"2026-11-09"),result("2026-11-09").json()).put(ContentDates.key(scope,"2026-11-07"),empty.json());
        // Write the exact legacy layout, even when this runner is installed against an older APK.
        Files.write(new File(root,"ai-results-v1.json").toPath(),new JSONObject().put("results",rows).put("cacheProfile","history-upgrade").toString().getBytes(StandardCharsets.UTF_8));
        out.putString("result","PASS");out.putString("history","legacy partial dates and empty daily result seeded on old APK");
      }else{
        checkpoint="upgrade migration";AtomicInteger calls=new AtomicInteger();AiClient fake=new AiClient((config,path,request)->{calls.incrementAndGet();throw new IOException("unexpected model request");});Handler handler=new Handler(Looper.getMainLooper());runOnMainSync(()->{coordinator[0]=new AiCoordinator(vault,handler,disk,()->{},fake);coordinator[0].load();});waitUntil(()->!coordinator[0].loading);disk.submit(()->{}).get(10,TimeUnit.SECONDS);
        check(coordinator[0].daily(scope,"2026-11-08")!=null&&coordinator[0].daily(scope,"2026-11-07").groups.isEmpty()&&calls.get()==0,"Upgrade must fill missing legacy day and retain empty day without model calls");
        check(new AiVault(root).loadResults().getJSONObject("results").has(key),"Migrated day did not persist");
        checkpoint="real daily storage >6MiB";JSONObject rows=new JSONObject();char[] fill=new char[600000];Arrays.fill(fill,'x');for(int i=1;i<=12;i++){String date=String.format(Locale.ROOT,"2026-10-%02d",i);AiEngine.Result r=result(date);r.text=new String(fill);rows.put(ContentDates.key(scope,date),r.json());}vault.saveResults(new JSONObject().put("results",rows),vault.revision());check(new AiVault(root).loadResults().getJSONObject("results").length()==12,"Large dated history failed Android restart read");
        checkpoint="capacity failure and retry";runOnMainSync(()->coordinator[0].load());waitUntil(()->!coordinator[0].loading);disk.submit(()->{}).get(10,TimeUnit.SECONDS);byte[] oldHead=Files.readAllBytes(new File(root,"ai-results-v1.json").toPath());AiEngine.Result huge=new AiEngine.Result();huge.profile="history-upgrade";huge.model="history-model";char[] oversize=new char[6*1024*1024+1];Arrays.fill(oversize,'z');huge.text=new String(oversize);runOnMainSync(()->{coordinator[0].results.put(AiEngine.NEWS,huge);coordinator[0].retrySave();});waitUntil(()->!coordinator[0].saveError.isEmpty());check(Arrays.equals(oldHead,Files.readAllBytes(new File(root,"ai-results-v1.json").toPath())),"Failed write changed committed head");check(!coordinator[0].savedState(AiEngine.NEWS).equals("已保存到本机"),"Failed result claimed durable");runOnMainSync(()->{coordinator[0].results.remove(AiEngine.NEWS);coordinator[0].retrySave();});waitUntil(()->coordinator[0].saveError.isEmpty());
        byte[] recoverHead=Files.readAllBytes(new File(root,"ai-results-v1.json").toPath());checkpoint="protected corrupted head";Files.write(new File(root,"ai-results-v1.json").toPath(),"broken-history".getBytes(StandardCharsets.UTF_8));runOnMainSync(()->coordinator[0].load());waitUntil(()->!coordinator[0].loading);check(coordinator[0].resultsBlocked&&!coordinator[0].loadError.isEmpty(),"Corrupted history must explicitly block replacement");runOnMainSync(()->coordinator[0].retrySave());disk.submit(()->{}).get(10,TimeUnit.SECONDS);check(new String(Files.readAllBytes(new File(root,"ai-results-v1.json").toPath()),StandardCharsets.UTF_8).equals("broken-history"),"Corrupted history was overwritten by automatic empty state");
        Files.write(new File(root,"ai-results-v1.json").toPath(),recoverHead);runOnMainSync(()->coordinator[0].load());waitUntil(()->!coordinator[0].loading);check(!coordinator[0].resultsBlocked&&coordinator[0].results.size()==12,"Retry read did not recover protected history");
        checkpoint="cleanup and reopen";runOnMainSync(()->coordinator[0].deleteDates(day->day.compareTo("2026-10-07")<0));disk.submit(()->{}).get(10,TimeUnit.SECONDS);check(new AiVault(root).loadResults().getJSONObject("results").length()==6,"Deleted dates resurrected on Android storage reload");
        out.putString("result","PASS");out.putString("history","legacy upgrade/missing dates/empty result; >6MiB real AtomicFile restart; failed save preserves head and exposes retry; corrupt read protects files; cleanup reload");
      }
    }catch(Throwable e){out.putString("result","FAIL");out.putString("error",checkpoint+": "+e.toString());}
    finally{if(coordinator[0]!=null)coordinator[0].close();disk.shutdown();}finish("PASS".equals(out.getString("result"))?-1:1,out);
  }
}
