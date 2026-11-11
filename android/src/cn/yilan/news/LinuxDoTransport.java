package cn.yilan.news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Fixed-host ECH transport. Other sources retain the original HTTPS path. */
public final class LinuxDoTransport implements NewsEngine.Transport {
  private final String cacheDir;
  private static boolean loaded;
  public LinuxDoTransport(String cacheDir){ this.cacheDir = cacheDir; }
  private static synchronized void load()throws IOException{
    if(loaded)return;
    try{System.loadLibrary("yilan_linuxdo");loaded=true;}
    catch(UnsatisfiedLinkError|SecurityException e){throw new IOException("当前设备无法加载 linux.do 加密连接组件",e);}
  }
  @Override public String get(NewsEngine.Source source,String endpoint)throws Exception{
    if(!"linuxdo".equals(source.id))return NewsEngine.download(source,endpoint);
    if(!"https://linux.do/latest.rss".equals(endpoint))throw new IOException("已阻止非登记 linux.do 接口");
    load();byte[] bytes=nativeGet(endpoint,cacheDir);
    if(bytes==null||bytes.length==0)throw new IOException("linux.do 未返回公开内容");
    if(bytes.length>NewsEngine.MAX_BODY)throw new IOException("响应超过 5 MB");
    return new String(bytes,StandardCharsets.UTF_8);
  }
  private static native byte[] nativeGet(String endpoint,String cacheDir)throws IOException;
}
