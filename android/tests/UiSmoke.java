package cn.yilan.news.test;

import android.app.*;
import android.content.*;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import cn.yilan.news.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Runs only in a fresh disposable AVD. Fake transports never contact news sites. */
public final class UiSmoke extends Instrumentation {
  private Activity activity;
  @Override public void onCreate(Bundle args){super.onCreate(args);start();}
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  private Object get(String name){try{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
  private void set(String name,Object value){try{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new RuntimeException(e);}}
  private Object call(String name,Class<?>[] types,Object... args){try{Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
  private void call(String name){call(name,new Class<?>[0]);}
  private void render(){call("render",new Class<?>[]{boolean.class},false);}
  private void main(Runnable task){final Throwable[] error={null};runOnMainSync(()->{try{task.run();}catch(Throwable failure){error[0]=failure;}});if(!((Boolean)get("busy")))waitForIdleSync();if(error[0]!=null)throw new RuntimeException(error[0]);}
  private void page(int tab,int mode){main(()->{set("tab",tab);set("aiMode",mode);call("drawNav");render();});}
  private List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
  private TextView exact(String value){for(TextView t:texts((View)get("body")))if(t.getText().toString().equals(value))return t;return null;}
  private String asset(String name)throws Exception{try(InputStream in=getTargetContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
  private NewsEngine.Article item(NewsEngine.Source source,String title)throws Exception{
    return NewsEngine.parse(source,"<rss><channel><item><title>"+title+"</title><link>"+source.home+"/ui390-"+title+"</link></item></channel></rss>",System.currentTimeMillis()).get(0);
  }
  @SuppressWarnings("unchecked") private Map<String,NewsEngine.Snapshot> snapshots(){return (Map<String,NewsEngine.Snapshot>)get("snapshots");}
  private void screen(String name)throws Exception{
    if(!((Boolean)get("busy")))waitForIdleSync();SystemClock.sleep(500);
    // Shell-owned output in this disposable AVD avoids changing app storage permissions.
    try(InputStream pipe=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/ui390-"+name+".png"))){byte[] buffer=new byte[1024];while(pipe.read(buffer)!=-1){}}
  }
  private void theme(String mode){main(()->{((NewsState)get("savedState")).themeMode=mode;call("applyTheme");call("chrome");render();});}
  private void await(String field,Object expected)throws Exception{
    long end=SystemClock.uptimeMillis()+8000;while(SystemClock.uptimeMillis()<end){final Object[] value={null};main(()->value[0]=get(field));if(expected.equals(value[0]))return;SystemClock.sleep(50);}throw new AssertionError("Timed out waiting for "+field+"="+expected);
  }
  private void layout(NewsEngine engine)throws Exception{
    main(()->{
      snapshots().clear();NewsEngine.Snapshot failure=new NewsEngine.Snapshot("npr");failure.error="Synthetic offline error";snapshots().put(failure.source,failure);
      NewsEngine.Snapshot many=new NewsEngine.Snapshot("ifanr");try{for(int i=0;i<3;i++)many.items.add(item(engine.sourceMap.get("ifanr"),"Fixture"+i));}catch(Exception e){throw new RuntimeException(e);}snapshots().put(many.source,many);
    });
    theme("day");page(3,0);
    main(()->{
      LinearLayout body=(LinearLayout)get("body");for(String name:new String[]{"外观","AI 功能","缓存管理","消息来源"})check(exact(name)!=null&&exact(name).getParent().getParent()==body,"Peer settings heading outside card: "+name);
      check(exact("阅读")==null,"Retired reading section");check(exact("分享安装包 ↗").getBackground() instanceof ColorDrawable,"Share action is unboxed");
      LinearLayout group=body.findViewWithTag("settings-sources");check(group.getChildCount()==engine.sources.size(),"One outer group holds all source cards");
      String[] first={"LINUX DO","NPR","爱范儿"};
      for(int i=0;i<first.length;i++)check(texts(group.getChildAt(i)).get(0).getText().toString().startsWith(first[i]),"Rendered source order "+i);
      for(NewsEngine.Source source:engine.sources)check(exact(source.home)==null,"No raw homepage in compact cards");
      TextView title=texts(group.getChildAt(0)).get(0);check(title.hasOnClickListeners(),"Source heading opens browser");check(exact("接口与模型")!=null&&exact("打开缓存日历")!=null&&exact("背景插画")!=null&&exact("界面动效")!=null,"AI/cache and appearance controls are visible");
    });screen("settings-day");theme("night");
    final String[] opened={null};
    ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction())){opened[0]=intent.getDataString();return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
    addMonitor(monitor);
    try{main(()->{
      TextView day=exact("日间");check(day.getCurrentTextColor()==ReaderTheme.forMode("day").ink&&day.getWidth()>0,"Day preview keeps contrasting text in night theme");
      for(TextView title:texts((View)get("body")))if(title.getText().toString().startsWith("LINUX DO")){title.performClick();break;}
    });check("https://linux.do".equals(opened[0]),"Source heading sends its homepage to the system browser");}finally{removeMonitor(monitor);}
    screen("settings-night");
    page(0,0);main(()->{check(exact("全部平台 ▾")!=null&&exact("全部标签 ▾")!=null,"News has two filter levels");});screen("news-filters");
    page(1,0);main(()->{check(exact("全部来源 ▾")!=null&&exact("全部厂商 ▾")==null,"Platform uses source selector");});screen("platform-filters");
    page(1,2);main(()->{
      LinearLayout body=(LinearLayout)get("body");List<TextView> top=texts(body.getChildAt(0));boolean ai=false;for(TextView t:top){check(!t.getText().toString().equals("模型"),"Model is no longer a top-level page");ai|=t.getText().toString().equals("AI");}check(ai,"AI catalog top-level label");
      check(exact("Harness")!=null&&exact("SDK")!=null&&exact("模型")!=null,"Model is sibling of Harness and SDK");set("toolType","model");render();
    });screen("ai-models");
  }
  private void bulk(String config)throws Exception{
    CountDownLatch release=new CountDownLatch(1);
    NewsEngine fake=new NewsEngine(config,(source,url)->{
      if(source.id.equals("ifanr")&&!release.await(30,TimeUnit.SECONDS))throw new IOException("Test barrier timeout");
      return "<rss><channel><item><title>UI refresh fixture</title><link>"+source.home+"/ui390-refresh</link></item></channel></rss>";
    });
    final View[] header={null};
    try{
      main(()->{
        snapshots().clear();set("engine",fake);set("tab",1);set("aiMode",0);render();header[0]=((ViewGroup)get("body")).getChildAt(0);
        call("startRefresh",new Class<?>[]{List.class,boolean.class,boolean.class,String.class,boolean.class},Arrays.asList(fake.sourceMap.get("ithome"),fake.sourceMap.get("ifanr")),true,false,"",false);
      });
      await("done",1);main(()->{
        check(((ViewGroup)get("body")).getChildAt(0)==header[0],"Per-source completion must preserve header view and scroll state");
        ProgressBar progress=(ProgressBar)get("progress");check(progress.getVisibility()==View.VISIBLE&&!progress.isIndeterminate()&&progress.getProgress()==1&&progress.getMax()==2,"Bulk progress reflects completed sources");
      });
    }finally{release.countDown();}await("busy",false);
  }
  @SuppressWarnings("unchecked") private void routeAI(NewsEngine engine)throws Exception{
    NewsEngine.Article ai=item(engine.sourceMap.get("infoq-cn"),"AI推理平台发布"),general=item(engine.sourceMap.get("infoq-cn"),"Java数据库架构实践");
    NewsEngine.Article original=item(engine.sourceMap.get("ithome"),"AI图像模型发布"),duplicate=item(engine.sourceMap.get("ai-media-ithome"),original.title);
    main(()->{
      set("engine",engine);snapshots().clear();set("query","");set("unread",false);set("newsRegion","all");set("feedRegion","all");((Set<String>)get("newsTags")).clear();((Set<String>)get("feedSources")).clear();
      for(NewsEngine.Article article:new NewsEngine.Article[]{ai,general,original,duplicate}){
        ((Map<String,Boolean>)get("sourceEnabled")).put(article.source,true);
        NewsEngine.Snapshot snapshot=snapshots().get(article.source);if(snapshot==null){snapshot=new NewsEngine.Snapshot(article.source);snapshots().put(article.source,snapshot);}snapshot.items.add(article);
      }
    });
    page(0,0);main(()->check(exact(general.title)==null&&exact(ai.title)==null&&exact(original.title)==null,"News keeps general engineering and removes AI posts from mixed feeds"));
    page(1,0);main(()->{
      check(exact(ai.title)!=null&&exact(general.title)!=null,"InfoQ AI article is visible only in the AI platform stream");
      ((Set<String>)get("feedSources")).add("ai-media-ithome");render();
      List<NewsEngine.Article> selected=(List<NewsEngine.Article>)call("articles",new Class<?>[]{boolean.class},true);
      check(selected.size()==1&&selected.get(0).id.equals(duplicate.id)&&exact(duplicate.title)!=null,"Feed filtering precedes URL deduplication and retains the selected source identity");
      ((Set<String>)get("feedSources")).clear();
    });
  }
  @SuppressWarnings("unchecked") private void linux(String config)throws Exception{
    CountDownLatch release=new CountDownLatch(1);
    NewsEngine fake=new NewsEngine(config,(source,url)->{
      if(!release.await(30,TimeUnit.SECONDS))throw new IOException("Test barrier timeout");
      return "<rss><channel><item><title>Linux progress fixture</title><link>https://linux.do/t/topic/123456789</link></item></channel></rss>";
    });
    try{
      main(()->{
        set("engine",fake);((Map<String,Boolean>)get("sourceEnabled")).put("linuxdo",true);set("linuxTab",0);set("tab",1);set("aiMode",1);render();
        TextView option=exact("默认更新 ▾");check(option!=null&&option.getBackground() instanceof ColorDrawable,"Linux selector is transparent text");
        call("refreshLinuxTab");ProgressBar progress=(ProgressBar)get("progress");check(progress.getVisibility()==View.VISIBLE&&progress.isIndeterminate(),"Linux uses the shared animated progress bar");
        check(!((Button)get("linuxUpdateBtn")).isEnabled(),"No duplicate Linux refresh while active");
      });screen("linux-progress");theme("day");
      main(()->check(((ProgressBar)get("progress")).isIndeterminate(),"Theme rebuild preserves in-flight Linux progress"));
    }finally{release.countDown();}await("busy",false);
    main(()->check(((ProgressBar)get("progress")).getVisibility()!=View.VISIBLE,"Progress hides on completion while keeping its layout slot"));
    final NewsEngine.Snapshot previous=((Map<String,NewsEngine.Snapshot>)get("linuxTabs")).get("default");
    NewsEngine denied=new NewsEngine(config,(source,url)->{throw new IOException("站点要求访问验证（HTTP 403）；Cloudflare challenge 标记存在");});
    main(()->{set("engine",denied);call("refreshLinuxTab");});await("busy",false);
    main(()->{
      NewsEngine.Snapshot failed=((Map<String,NewsEngine.Snapshot>)get("linuxTabs")).get("default");
      check(failed.items.size()==previous.items.size()&&failed.fetched==previous.fetched,"403 keeps the old Linux snapshot");
      check(((ProgressBar)get("progress")).getVisibility()!=View.VISIBLE,"403 also stops the progress animation");
      TextView details=exact("本站暂时拒绝访问（403） · 已保留 "+failed.items.size()+" 条，点击查看错误详情");check(details!=null&&details.hasOnClickListeners(),"403 has a compact summary and an explicit details action");
    });
  }
  @Override public void onStart(){
    Bundle report=new Bundle();
    try{
      String config=asset("sources.json");NewsEngine engine=new NewsEngine(config);
      activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.yilan.news","cn.yilan.news.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      layout(engine);routeAI(engine);bulk(config);linux(config);
      report.putString("result","PASS");report.putString("ui","settings/filter/catalog layouts; per-article AI routing and selected-source deduplication; stable bulk header; shared Linux progress; theme during refresh");report.putString("screenshots","/data/local/tmp/ui390-*.png");
    }catch(Throwable e){report.putString("result","FAIL");report.putString("error",e.toString());try{screen("failure");}catch(Exception ignored){}}
    finally{if(activity!=null)runOnMainSync(()->activity.finish());}
    finish("PASS".equals(report.getString("result"))?-1:1,report);
  }
}
