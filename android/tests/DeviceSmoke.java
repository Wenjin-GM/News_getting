package cn.yilan.news.test;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.AtomicFile;
import cn.yilan.news.NewsEngine;
import cn.yilan.news.NewsState;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Separate signed test APK, run only on the disposable workspace emulator. */
public final class DeviceSmoke extends Instrumentation {
  private String mode;
  @Override public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","verify");start();}
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  private String asset(String name)throws Exception{
    try(InputStream in=getTargetContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toString("UTF-8");
    }
  }
  @Override public void onStart(){
    Bundle report=new Bundle();
    try{
      NewsEngine engine=new NewsEngine(asset("sources.json"));NewsState state=new NewsState();
      AtomicFile file=new AtomicFile(new File(getTargetContext().getFilesDir(),"native-news-v3.json"));
      if("seed".equals(mode)){
        state.restore(engine,new JSONObject());
        NewsEngine.Article item=fixture(engine.sourceMap.get("ithome"));state.favorites.add(item);state.read.add(item.id);
        state.sourceEnabled.put("ithome",false);state.themeMode="night";
        NewsEngine.Snapshot snap=new NewsEngine.Snapshot(item.source);snap.items.add(item);snap.fetched=snap.checked=1;state.snapshots.put(item.source,snap);
        FileOutputStream out=file.startWrite();try{out.write(state.json().toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}
        report.putString("state","SEEDED_ON_OLD_APK");
      }else{
        state.restore(engine,new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8)));
        NewsEngine.Article item=fixture(engine.sourceMap.get("ithome"));
        check(state.favorites.size()==1&&state.favorites.get(0).id.equals(item.id),"Favorite identity changed on upgrade");
        check(state.read.contains(item.id)&&!state.sourceEnabled.get("ithome")&&"night".equals(state.themeMode),"Reading/source/theme state changed on upgrade");
        check(state.snapshots.get("ithome").items.get(0).id.equals(item.id),"Cached article lost on upgrade");
        report.putString("state","PRESERVED_AFTER_UPGRADE");
        if("native".equals(mode))nativeProbe(engine,report);
      }
      report.putString("result","PASS");finish(-1,report);
    }catch(Throwable error){report.putString("result","FAIL");report.putString("error",error.toString());finish(1,report);}
  }
  private NewsEngine.Article fixture(NewsEngine.Source source)throws Exception{
    String host="linuxdo".equals(source.id)?"https://linux.do/t/topic/987654321":"https://www.ithome.com/0/test-upgrade.htm";
    return NewsEngine.parse(source,"<rss><channel><item><title>本机升级状态测试</title><link>"+host+"</link><description>仅用于隔离模拟器的测试记录</description></item></channel></rss>",System.currentTimeMillis()).get(0);
  }
  private void nativeProbe(NewsEngine original,Bundle report)throws Exception{
    Class<?> transport=Class.forName("cn.yilan.news.NewsEngine$Transport");
    Class<?> adapter=Class.forName("cn.yilan.news.LinuxDoTransport");Object instance=adapter.getConstructor().newInstance();
    Object wrapper=Proxy.newProxyInstance(transport.getClassLoader(),new Class<?>[]{transport},(proxy,method,args)->{
      try{return adapter.getMethod("get",NewsEngine.Source.class,String.class).invoke(instance,args);}
      catch(InvocationTargetException e){throw e.getCause();}
    });
    NewsEngine engine=(NewsEngine)NewsEngine.class.getConstructor(String.class,transport).newInstance(asset("sources.json"),wrapper);
    NewsEngine.Source source=engine.sourceMap.get("linuxdo");check(source!=null,"Missing linux.do source");
    NewsEngine.Snapshot previous=new NewsEngine.Snapshot(source.id);previous.items.add(fixture(source));previous.fetched=previous.checked=1;
    NewsEngine.Snapshot result=engine.fetch(source,previous,true);
    check(!result.error.contains("无法加载"),"Android could not load native library: "+result.error);
    report.putString("native","JNI_CALLED");report.putString("sourceError",result.error);
    if(!result.error.isEmpty()){
      check(result.items.size()==1&&result.items.get(0).id.equals(previous.items.get(0).id)&&result.fetched==1,"Real native failure erased previous cache");
      report.putString("fetch",result.error.contains("访问验证")?"ACCESS_VERIFICATION_REQUIRED":"NETWORK_UNAVAILABLE");
      report.putString("failureRetention","PASS");
    }else{
      check(!result.items.isEmpty(),"Successful fetch returned no articles");report.putString("fetch","PUBLIC_RSS_FETCHED");report.putInt("items",result.items.size());
    }
  }
}
