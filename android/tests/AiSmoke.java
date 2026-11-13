package cn.yilan.news.test;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import cn.yilan.news.*;
import cn.yilan.news.NewsEngine.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Synthetic API only, in a new isolated AVD. No real provider key or paid request. */
public final class AiSmoke extends Instrumentation {
  private Activity activity;private AiCoordinator service;private ExecutorService disk;
  private static final String KEY="Android-instrumentation-synthetic-credential";
  private String checkpoint="setup";private volatile boolean fail,holdFeatured=true;
  private final CountDownLatch featuredGate=new CountDownLatch(1);
  private final java.util.concurrent.atomic.AtomicInteger activeCalls=new java.util.concurrent.atomic.AtomicInteger(),peakCalls=new java.util.concurrent.atomic.AtomicInteger(),reportsDone=new java.util.concurrent.atomic.AtomicInteger();
  private final Set<String> stages=new HashSet<>();private boolean invalidProgress;
  private final Set<String> requested=Collections.synchronizedSet(new HashSet<>());
  private final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
  @Override public void onCreate(Bundle args){super.onCreate(args);start();}
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  private Object get(String name){try{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
  private void set(String name,Object value){try{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new RuntimeException(e);}}
  private Object call(String name,Class<?>[] types,Object...args){try{Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
  private void main(Runnable task){final Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable e){failure[0]=e;}});if((service==null||!service.running)&&!((Boolean)get("busy")))waitForIdleSync();if(failure[0]!=null)throw new RuntimeException(failure[0]);}
  private void waitUntil(java.util.function.BooleanSupplier condition)throws Exception{long end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){final boolean[] ready={false};main(()->ready[0]=condition.getAsBoolean());if(ready[0])return;SystemClock.sleep(30);}throw new AssertionError("AI runtime condition timed out");}
  private List<View> views(View root){List<View> out=new ArrayList<>();out.add(root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)out.addAll(views(group.getChildAt(i)));}return out;}
  private Button button(View root,String text){for(View v:views(root))if(v instanceof Button&&((Button)v).getText().toString().equals(text))return (Button)v;throw new AssertionError("Missing button "+text);}
  private AccessibilityNodeInfo node(String text,boolean exact)throws Exception{long end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text))if(n.getText()!=null&&(exact?n.getText().toString().equals(text):n.getText().toString().contains(text)))return n;SystemClock.sleep(60);}throw new AssertionError("Missing visible AI control: "+text);}
  private void shot(String name)throws Exception{if((service==null||!service.running)&&!((Boolean)get("busy")))waitForIdleSync();SystemClock.sleep(500);try(InputStream pipe=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/ai400-"+name+".png"))){byte[] buffer=new byte[1024];while(pipe.read(buffer)!=-1){}}}
  private String reply(String model,String content)throws Exception{return new JSONObject().put("model",model).put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content",content)))).toString();}
  private Article article(Source source,String title,String id)throws Exception{
    return NewsEngine.parse(source,"<rss><channel><item><title>"+title+"</title><link>"+source.home+"/ai-test-"+id+"</link></item></channel></rss>",System.currentTimeMillis()).get(0);
  }
  @Override public void onStart(){
    Bundle report=new Bundle();
    try{
      new AiVault(getTargetContext().getFilesDir()).forget();
      activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.yilan.news","cn.yilan.news.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      waitUntil(()->!((AiCoordinator)get("aiService")).loading);
      AiVault vault=new AiVault(getTargetContext().getFilesDir());disk=Executors.newSingleThreadExecutor();Handler handler=new Handler(Looper.getMainLooper());
      AiClient fake=new AiClient((config,path,body)->{
        if(path.equals("/models"))return "{\"data\":[{\"id\":\"text-first\"},{\"id\":\"text-second\"}]}";
        check(config.model.equals("text-second")&&body.getString("model").equals("text-second"),"Locked model changed");requested.add(config.model);requests.incrementAndGet();
        check(!body.toString().contains(KEY)&&!body.toString().contains("BODY_PRIVATE_SENTINEL"),"API payload leaked credentials or article body");
        if(fail)throw new AiClient.Failure(429,"HTTP 429 synthetic failure");
        int active=activeCalls.incrementAndGet();peakCalls.accumulateAndGet(active,Math::max);
        try{
        String system=body.getJSONArray("messages").getJSONObject(0).getString("content");
        if(system.contains("groups")&&holdFeatured){check(reportsDone.get()==2,"Featured started before both nonempty reports settled");check(featuredGate.await(30,TimeUnit.SECONDS),"Featured test gate timed out");}
        Thread.sleep(650);
        if(system.contains("新闻编辑")&&!system.contains("groups"))reportsDone.incrementAndGet();
        return reply(config.model,system.contains("groups")?"{\"groups\":[{\"title\":\"AI推荐标题\",\"reason\":\"根据标题选择\",\"ids\":[\"r0\",\"r0\"]}]}":system.contains("翻译")?"这是测试译文":"这是标题分析汇报");
        }finally{activeCalls.decrementAndGet();}
      });
      main(()->{((AiCoordinator)get("aiService")).close();service=new AiCoordinator(vault,handler,disk,()->{call("aiChanged",new Class<?>[0]);
        if(service.running){stages.add(service.stage);ProgressBar bar=(ProgressBar)get("progress");Button update=(Button)get("refresh");invalidProgress|=bar.getVisibility()!=View.VISIBLE||!update.getText().toString().equals("正在"+service.stage)||update.getLayoutParams().width!=-1&&!((ReaderUpdateTransition)get("updateTransition")).isAnimating()||bar.getProgress()>bar.getMax()||update.getCurrentTextColor()!=((ReaderTheme)get("palette")).onAccent;}
},fake);set("aiService",service);set("tab",3);call("render",new Class<?>[]{boolean.class},false);});
      final AlertDialog[] dialog={null};final List<EditText> edits=new ArrayList<>();
      main(()->{
        dialog[0]=AiSettingsDialog.show((Context)get("ui"),null,"",fake,service.tests,handler,c->service.save(c,error->check(error.isEmpty(),"Encrypted settings save failed")),service::forget);
        for(View v:views(dialog[0].getWindow().getDecorView()))if(v instanceof EditText)edits.add((EditText)v);
        check(edits.size()==3&&!dialog[0].getButton(-1).isEnabled(),"No model is automatically selected or saved");edits.get(0).setText("https://api.deepseek.com");edits.get(1).setText(KEY);
        check(!edits.get(1).isSaveEnabled(),"Credential field must not enter saved UI state");button(dialog[0].getWindow().getDecorView(),"测试连接并获取模型列表").performClick();
      });
      waitUntil(()->{for(View v:views(dialog[0].getWindow().getDecorView()))if(v instanceof RadioGroup)return ((RadioGroup)v).getChildCount()==2;return false;});
      shot("settings");
      main(()->{
        check(edits.get(2).getText().toString().isEmpty(),"Model list did not silently choose the first model");
        for(View v:views(dialog[0].getWindow().getDecorView()))if(v instanceof RadioGroup)((RadioGroup)v).getChildAt(1).performClick();
        check(!dialog[0].getButton(-1).isEnabled(),"Selection alone is not a usable-model test");button(dialog[0].getWindow().getDecorView(),"测试所选模型（极短请求）").performClick();
      });
      waitUntil(()->dialog[0].getButton(-1).isEnabled());main(()->dialog[0].getButton(-1).performClick());waitUntil(()->service.enabled()&&!service.saving);
      AiClient.Config saved=vault.load();check(saved.model.equals("text-second")&&saved.key.equals(KEY),"Encrypted config roundtrip failed");
      String ciphertext=new String(Files.readAllBytes(new File(getTargetContext().getFilesDir(),"ai-settings-v1.enc").toPath()),StandardCharsets.UTF_8);check(!ciphertext.contains(KEY)&&!ciphertext.contains("text-second"),"Settings stored plaintext");
      check(!((NewsState)get("savedState")).json().toString().contains(KEY),"Legacy news state contains API credentials");
      NewsEngine engine=(NewsEngine)get("engine");JSONObject catalog=(JSONObject)get("catalog");
      Article news=article(engine.sourceMap.get("chinanews"),"城市公共服务发布","news"),ai=article(engine.sourceMap.get("infoq-cn"),"AI模型发布","ai");news.published=ai.published=System.currentTimeMillis();news.summary=ai.summary="BODY_PRIVATE_SENTINEL";
      Map<String,Snapshot> snapshots=new HashMap<>();for(Article a:new Article[]{news,ai}){Snapshot snapshot=new Snapshot(a.source);snapshot.fetched=System.currentTimeMillis();snapshot.items.add(a);snapshots.put(a.source,snapshot);}
      Map<String,Boolean> enabled=new HashMap<>();enabled.put(news.source,true);enabled.put(ai.source,true);List<Article> archive=Arrays.asList(news,ai);
      String sourceConfig=(String)call("asset",new Class<?>[]{String.class},"sources.json");
      NewsEngine collected=new NewsEngine(sourceConfig,(source,url)->{
        String title=source.id.equals("infoq-cn")?"AI模型发布":"城市公共服务发布";
        String date=java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
        return "<rss><channel><item><title>"+title+"</title><link>"+source.home+"/ai-auto-update</link><pubDate>"+date+"</pubDate></item></channel></rss>";
      });
      main(()->{
        set("tab",0);set("featuredMode",false);set("hotMode",false);set("query","");set("engine",collected);call("render",new Class<?>[]{boolean.class},false);((Map<?,?>)get("snapshots")).clear();
        @SuppressWarnings("unchecked") Map<String,Boolean> switches=(Map<String,Boolean>)get("sourceEnabled");for(String id:new ArrayList<>(switches.keySet()))switches.put(id,false);switches.put("chinanews",true);switches.put("infoq-cn",true);
        call("startRefresh",new Class<?>[]{List.class,boolean.class,boolean.class,String.class,boolean.class},Arrays.asList(collected.sourceMap.get("chinanews"),collected.sourceMap.get("infoq-cn")),true,false,"",false);
        check(((Button)get("refresh")).getText().toString().equals("正在获取新闻")&&((ProgressBar)get("progress")).getVisibility()==View.VISIBLE,"Fetch phase must show expanded button and progress");
      });
      checkpoint="report stage";waitUntil(()->service.running&&service.stage.equals("生成汇报"));shot("report-progress");
      checkpoint="featured stage";waitUntil(()->service.running&&service.stage.equals("生成精选"));
      main(()->{boolean visible=false;for(View v:views((View)get("body")))if(v instanceof TextView&&((TextView)v).getText().toString().equals("这是标题分析汇报"))visible=true;
        check(visible&&service.results.containsKey(AiEngine.NEWS),"New report must be visible while featured requests are still blocked");
        check(((android.animation.ValueAnimator)get("refreshSpin")).isStarted(),"Refresh glyph must animate during AI work");
      });
      final int[] rotation={0};main(()->rotation[0]=((android.graphics.drawable.RotateDrawable)get("refreshGlyph")).getLevel());SystemClock.sleep(150);main(()->check(rotation[0]!=((android.graphics.drawable.RotateDrawable)get("refreshGlyph")).getLevel(),"Animated icon must advance while waiting for the service"));
      shot("featured-progress");holdFeatured=false;featuredGate.countDown();
      waitUntil(()->!((Boolean)get("busy"))&&!service.running&&service.results.size()>=5);
      waitUntil(()->!((ReaderUpdateTransition)get("updateTransition")).isAnimating());main(()->check(!invalidProgress&&stages.contains("生成汇报")&&stages.contains("生成精选")&&((Button)get("refresh")).getText().toString().equals("更新")&&((Button)get("refresh")).getLayoutParams().width==-2&&((ProgressBar)get("progress")).getVisibility()!=View.VISIBLE&&!((android.animation.ValueAnimator)get("refreshSpin")).isStarted(),"All stages show current progress and restore idle header"));
      check(service.results.size()>=5&&service.results.get(AiEngine.FEATURED_AI).groups.size()==1,"Five AI sections were not generated");
      main(()->{set("tab",0);set("featuredMode",false);set("hotMode",false);call("drawNav",new Class<?>[0]);call("render",new Class<?>[]{boolean.class},false);});shot("report");
      main(()->{set("tab",1);set("aiMode",4);call("drawNav",new Class<?>[0]);call("render",new Class<?>[]{boolean.class},false);});shot("featured");
      check(peakCalls.get()==2,"AI calls must be bounded to two concurrent requests");
      int beforeReuse=requests.get();
      main(()->service.run(collected,catalog,(Map<String,Snapshot>)get("snapshots"),(Map<String,Boolean>)get("sourceEnabled"),((NewsArchive)get("archive")).articles()));waitUntil(()->!service.running);
      check(requests.get()==beforeReuse&&service.results.get(AiEngine.NEWS).reused>0,"An unchanged update should reuse batches without any API request");
      main(service.batchCache::clear);
      long previous=service.results.get(AiEngine.FEATURED_AI).at;fail=true;main(()->service.run(engine,catalog,snapshots,enabled,archive));waitUntil(()->!service.running);
      check(service.results.get(AiEngine.FEATURED_AI).at==previous&&service.errors.containsKey(ContentDates.key(AiEngine.FEATURED_AI,FeaturedNews.day(System.currentTimeMillis()))),"Failure erased previous AI featured or failed to report error");
      check(requested.size()==1&&requested.contains("text-second"),"A fallback model was requested");disk.submit(()->{}).get(5,TimeUnit.SECONDS);
      final AiCoordinator[] restored={null};main(()->{restored[0]=new AiCoordinator(vault,handler,disk,()->{},fake);restored[0].load();});waitUntil(()->!restored[0].loading);
      check(restored[0].enabled()&&restored[0].results.get(AiEngine.FEATURED_AI).at==previous&&restored[0].errors.containsKey(ContentDates.key(AiEngine.FEATURED_AI,FeaturedNews.day(System.currentTimeMillis()))),"Restart did not retain locked model and last AI selection/error");restored[0].close();
      fail=false;int beforeRetry=requests.get();long unchangedAI=service.results.get(AiEngine.FEATURED_AI).at;
      main(()->service.run(engine,catalog,snapshots,enabled,archive,AiEngine.NEWS));waitUntil(()->!service.running);
      check(requests.get()==beforeRetry+1&&!service.errors.containsKey(AiEngine.NEWS)&&service.errors.containsKey(ContentDates.key(AiEngine.FEATURED_AI,FeaturedNews.day(System.currentTimeMillis())))&&service.results.get(AiEngine.FEATURED_AI).at==unchangedAI,"Retrying news report must not redo successful or failed unrelated partitions");
      final String[] translated={null};main(()->service.translate("English article preview",value->translated[0]=value,error->translated[0]="FAILED"));waitUntil(()->translated[0]!=null);check(translated[0].contains("译文"),"Manual translation did not use selected model");
      Article foreign=article(engine.sourceMap.get("npr"),"Foreign release report","translation-ui");foreign.summary="English article preview.";int before=requests.get();
      main(()->call("open",new Class<?>[]{Article.class},foreign));AccessibilityNodeInfo translate=node("翻译",true);check(requests.get()==before,"Opening a foreign preview must not translate automatically");
      check(translate.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Translation action unavailable");node("译文（AI）",false);check(requests.get()==before+1,"Manual translation button did not make exactly one small request");shot("translation");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
      Files.write(new File(getTargetContext().getFilesDir(),"ai-results-v1.json").toPath(),"broken cache".getBytes(StandardCharsets.UTF_8));
      final AiCoordinator[] damaged={null};main(()->{damaged[0]=new AiCoordinator(vault,handler,disk,()->{},fake);damaged[0].load();});waitUntil(()->!damaged[0].loading);check(damaged[0].enabled()&&!damaged[0].loadError.isEmpty(),"Damaged result cache must not erase the locked model");damaged[0].close();
      long oldRevision=vault.revision();JSONObject stale=new JSONObject().put("results",new JSONObject());main(service::forget);vault.saveResults(stale,oldRevision);check(vault.load()==null&&vault.loadResults().length()==0,"Queued old results resurrected forgotten AI config/cache");
      report.putString("result","PASS");report.putString("ai","settings test/select/save; AndroidKeyStore encrypted roundtrip; five scopes; failure/restart retention; fixed model; translation; forget epoch guard");
    }catch(Throwable e){report.putString("result","FAIL");report.putString("error",checkpoint+": "+e.toString());if(service!=null)report.putString("debug","stage="+service.stage+"; results="+service.results.keySet()+"; errors="+service.errors);}
    finally{featuredGate.countDown();if(service!=null)service.close();if(disk!=null)disk.shutdown();if(activity!=null)runOnMainSync(()->activity.finish());}
    finish("PASS".equals(report.getString("result"))?-1:1,report);
  }
}
