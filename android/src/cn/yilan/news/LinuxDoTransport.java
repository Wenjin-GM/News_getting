package cn.yilan.news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Fixed-host ECH transport for the registered encrypted-SNI sources. Other
 * sources retain the original HTTPS path. */
public final class LinuxDoTransport implements NewsEngine.Transport {
  private final String cacheDir;
  private static boolean loaded;
  /** Numeric single-topic endpoint only; no slug/query/extra params. */
  private static final Pattern TOPIC=Pattern.compile("^https://linux.do/t/[0-9]+\\.json$");
  public LinuxDoTransport(String cacheDir){ this.cacheDir = cacheDir; }
  /** Sources served by the native ECH client. */
  private static boolean nativeSource(String id){
    return "linuxdo".equals(id) || "v2ex".equals(id) || "v2ex-latest".equals(id);
  }
  /** Exact registered endpoints, checked before the native library is loaded.
   * The native parse_endpoint whitelist enforces the same rules independently. */
  private static boolean allowedEndpoint(String endpoint){
    return "https://linux.do/latest.rss".equals(endpoint)
      || NewsEngine.LINUXDO_VIEWS_ENDPOINT.equals(endpoint)
      || NewsEngine.LINUXDO_LATEST_ENDPOINT.equals(endpoint)
      || TOPIC.matcher(endpoint).matches()
      || "https://www.v2ex.com/api/topics/hot.json".equals(endpoint)
      || "https://www.v2ex.com/index.xml".equals(endpoint);
  }
  private static synchronized void load()throws IOException{
    if(loaded)return;
    try{System.loadLibrary("yilan_linuxdo");loaded=true;}
    catch(UnsatisfiedLinkError|SecurityException e){throw new IOException("当前设备无法加载加密连接组件",e);}
  }
  @Override public String get(NewsEngine.Source source,String endpoint)throws Exception{
    if(!nativeSource(source.id))return NewsEngine.download(source,endpoint);
    if(!allowedEndpoint(endpoint))throw new IOException("已阻止非登记接口");
    load();byte[] bytes=nativeGet(endpoint,cacheDir);
    if(bytes==null||bytes.length==0)throw new IOException("未返回公开内容");
    if(bytes.length>NewsEngine.MAX_BODY)throw new IOException("响应超过 5 MB");
    return new String(bytes,StandardCharsets.UTF_8);
  }
  private static native byte[] nativeGet(String endpoint,String cacheDir)throws IOException;
}
