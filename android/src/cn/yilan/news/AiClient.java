package cn.yilan.news;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Small OpenAI-compatible text client. No SDK, tools, redirects or model fallback. */
public final class AiClient {
  public static final int LIMIT=1024*1024;
  public static final class Failure extends IOException {public final int status;public Failure(String message){this(0,message);}public Failure(int status,String message){super(message);this.status=status;}}
  public static final class Config {
    public final String url,key,model,id;
    public final boolean enabled;
    public Config(String url,String key,String model,String id,boolean enabled)throws IOException{
      this.url=baseURL(url);this.key=key.trim();this.model=model.trim();this.id=id;this.enabled=enabled;
      if(this.key.isEmpty()||this.key.length()>8192||this.key.contains("\n")||this.key.contains("\r"))throw new Failure("请填写有效 API Key");
      if(!this.model.isEmpty()&&!this.model.matches("[A-Za-z0-9_./:@+-]{1,160}"))throw new Failure("模型 ID 格式无效");
    }
    public Config withModel(String value)throws IOException{return new Config(url,key,value,id,enabled);}
    public JSONObject secretJSON()throws JSONException{return new JSONObject().put("url",url).put("key",key).put("model",model).put("id",id).put("enabled",enabled);}
    public static Config from(JSONObject j)throws Exception{return new Config(j.getString("url"),j.getString("key"),j.getString("model"),j.getString("id"),j.optBoolean("enabled",true));}
  }
  public static String baseURL(String input)throws IOException{
    try{
      URI uri=new URI(input.trim());String safe=NewsEngine.publicURL(input.trim());
      if(safe==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null)throw new Exception();
      String path=uri.getPath()==null?"":uri.getPath().replaceAll("/+$","");
      for(String suffix:new String[]{"/chat/completions","/models"})if(path.endsWith(suffix))path=path.substring(0,path.length()-suffix.length());
      if(uri.getHost().equalsIgnoreCase("api.openai.com")&&path.isEmpty())path="/v1";
      return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),-1,path,null,null).toASCIIString();
    }catch(Exception e){throw new Failure("请填写公开 HTTPS API 基础地址，不含密钥、查询参数或本地地址");}
  }
  public interface Transport {String request(Config config,String path,JSONObject body)throws Exception;}
  private final Transport transport;
  public AiClient(){this(new Http());}
  public AiClient(Transport transport){this.transport=transport;}
  public static JSONObject json(String text)throws Exception{
    if(text.getBytes(StandardCharsets.UTF_8).length>LIMIT)throw new Failure("AI 响应超过大小上限");
    boolean quoted=false,escape=false;int depth=0;
    for(char c:text.toCharArray())if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>32)throw new Failure("AI 响应嵌套过深");}else if(c=='}'||c==']')depth--;
    return new JSONObject(text);
  }
  public static final class Models {
    public final List<String> ids;public final boolean fromServer;public final String detail;
    Models(List<String> ids,boolean fromServer){this(ids,fromServer,"");}
    Models(List<String> ids,boolean fromServer,String detail){this.ids=ids;this.fromServer=fromServer;this.detail=detail;}
  }
  public Models models(Config config)throws Exception{
    try{
      JSONArray rows=json(transport.request(config,"/models",null)).getJSONArray("data");LinkedHashSet<String> ids=new LinkedHashSet<>();
      for(int i=0;i<Math.min(500,rows.length());i++){
        JSONObject row=rows.optJSONObject(i);if(row==null)continue;String id=row.optString("id");
        if(id.matches("[A-Za-z0-9_./:@+-]{1,160}"))ids.add(id);
      }
      if(ids.isEmpty())throw new Failure("接口未返回模型列表");return new Models(new ArrayList<>(ids),true);
    }catch(Exception e){
      // Official GLM documentation publishes chat IDs but does not guarantee /models.
      // These are explicitly unverified candidates, never claimed as key entitlements.
      if(new URI(config.url).getHost().equalsIgnoreCase("open.bigmodel.cn"))return new Models(Arrays.asList("glm-5.3","glm-5.3-flash","glm-5.2"),false,error(e));
      throw e;
    }
  }
  public void probe(Config config)throws Exception{complete(config,"只回复 OK。","连接测试",32);}
  public String complete(Config config,String system,String input,int tokens)throws Exception{
    if(config.model.isEmpty())throw new Failure("请先选择并验证模型");
    JSONObject body=new JSONObject().put("model",config.model).put("stream",false).put("max_tokens",tokens)
      .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(new JSONObject().put("role","user").put("content",input.replace(config.key,"[密钥已隐藏]"))));
    String host=new URI(config.url).getHost();
    if(host.equalsIgnoreCase("api.deepseek.com")||host.equalsIgnoreCase("open.bigmodel.cn")&&(config.model.startsWith("glm-5")||config.model.matches("glm-4\\.[567].*")))body.put("thinking",new JSONObject().put("type","disabled"));
    JSONObject reply=json(transport.request(config,"/chat/completions",body));
    if(reply.has("error"))throw new Failure("服务返回错误，未采用本次结果");
    String returned=reply.optString("model");if(!returned.isEmpty()&&!returned.equals(config.model))throw new Failure("响应模型与锁定 ID 不同，已拒绝结果；请在设置中确认");
    JSONObject choice=reply.getJSONArray("choices").getJSONObject(0);
    if("length".equals(choice.optString("finish_reason")))throw new Failure("模型输出被截断，旧结果已保留");
    String text=choice.getJSONObject("message").optString("content").replace(config.key,"[密钥已隐藏]").trim();
    if(text.isEmpty())throw new Failure("模型没有返回文本，未采用本次结果");
    return text;
  }
  public static String error(Throwable e){return e instanceof Failure?e.getMessage():"AI 请求失败（网络、超时或响应格式异常），旧结果已保留";}
  private static final class Http implements Transport {
    public String request(Config config,String path,JSONObject body)throws Exception{
      if(!path.equals("/models")&&!path.equals("/chat/completions"))throw new Failure("未知 AI 操作");
      URL url=new URL(config.url+path);
      for(InetAddress ip:InetAddress.getAllByName(url.getHost()))if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())throw new Failure("AI 地址解析到本地网络，已停止");
      HttpsURLConnection c=(HttpsURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setUseCaches(false);
      try{
        c.setRequestProperty("Authorization","Bearer "+config.key);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Accept-Encoding","identity");
        if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);if(data.length>256*1024)throw new Failure("AI 请求过大");c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
        int status=c.getResponseCode();if(status<200||status>=300)throw new Failure(status,"AI 接口 HTTP "+status+"（请检查地址、密钥、模型权限或额度；未切换模型）");
        if(c.getContentLengthLong()>LIMIT)throw new Failure("AI 响应超过大小上限");
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
          byte[] b=new byte[8192];int n;long end=System.nanoTime()+90000000000L;
          while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>end)throw new Failure("AI 操作已停止或超时");if(out.size()+n>LIMIT)throw new Failure("AI 响应超过大小上限");out.write(b,0,n);}return out.toString("UTF-8");
        }
      }finally{c.disconnect();}
    }
  }
}
