package cn.yilan.news;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

/** Pure Java collection/normalization engine. No WebView, cloud aggregator, account or model API. */
public final class NewsEngine {
  public static final int MAX_BODY=5*1024*1024;
  /** linux.do sub-page modes. */
  public static final int LINUXDO_MODE_DEFAULT=0,LINUXDO_MODE_VIEWS=1,LINUXDO_MODE_LATEST=2;
  /** Quarterly top (~90d) and latest list expanded to 50; manual only. */
  public static final String LINUXDO_VIEWS_ENDPOINT="https://linux.do/top.json?period=quarterly";
  public static final String LINUXDO_LATEST_ENDPOINT="https://linux.do/latest.json?per_page=50";
  public static final int LINUXDO_LIMIT=50;
  /** Neo is the forum system account; its posts are excluded from the views sub-page. */
  public static final String LINUXDO_SYSTEM_USER="neo";
  /** Views sub-page window: 90 days. */
  public static final long LINUXDO_WINDOW_MS=90L*86400000L;
  public static final List<String> HOT_SOURCES=Collections.unmodifiableList(Arrays.asList("toutiao","baidu","weibo","zhihu","douyin","bilibili","bilibili-video","tieba","v2ex","hackernews"));
  /** Separate source lists, never a numeric comparison of unrelated heat strings. */
  public static List<Article> hotItems(String source,Snapshot snapshot){
    List<Article> items=new ArrayList<>();
    if(!HOT_SOURCES.contains(source)||snapshot==null||!source.equals(snapshot.source))return items;
    for(Article a:snapshot.items)if(source.equals(a.source))items.add(a);
    items.sort(Comparator.comparingInt(a->a.rank));
    return items;
  }
  public static String heatLabel(Article a){return a.heat==null||a.heat.isEmpty()?"未提供":a.heat;}
  private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.DOTALL|Pattern.UNICODE_CASE;
  private static final Pattern AI=Pattern.compile("\\bAI\\b|人工智能|大模型|智能体|openai|chatgpt|codex|\\bgpt\\b|claude|anthropic|gemini|antigravity|deepseek|通义|千问|qwen|灵码|kimi|月之暗面|minimax|智谱|\\bglm\\b|zcode|grok|mistral|copilot|codebuddy|文心|trae|豆包|kiro|hugging.?face",FLAGS);
  private static final Pattern TECH=Pattern.compile("编程|程序员|代码|芯片|半导体|显卡|处理器|软件|开源|操作系统|手机系统|安卓|鸿蒙|互联网|浏览器|数据库|云计算|算法|开发者|\\b(?:android|ios|linux|windows|macos|github|cpu|gpu|sdk|api|python|java|javascript|rust)\\b",FLAGS);
  // AI-only routing is narrower than the existing AI-or-technology station rules.
  // ASCII boundaries recognize AI研发 while avoiding substrings such as daily/mail.
  private static final Pattern AI_TOPIC=Pattern.compile(AI.pattern()+"|(?<![a-z0-9])(?:AI|AIGC|LLMs?|AGI|BERT|GPT[- ]?[0-9]+)(?![a-z0-9])|机器学习|深度学习|神经网络|自然语言处理|语言模型|生成式模型|agentic|coding agents?",FLAGS);
  private static boolean aiTopic(String title,String summary){return AI_TOPIC.matcher(title+" "+summary).find();}
  public static boolean isAI(Article a){return aiTopic(a.title,a.summary);}
  public static boolean isAIOrTech(Article a){return "hackernews".equals(a.source)||AI.matcher(a.title+" "+a.summary).find()||TECH.matcher(a.title+" "+a.summary).find();}
  public static List<Article> hotItems(String source,Snapshot snapshot,boolean ai){
    List<Article> items=hotItems(source,snapshot);items.removeIf(a->isAIOrTech(a)!=ai);return items;
  }
  public static Set<String> aiHotURLs(Map<String,Snapshot> snapshots,Map<String,Boolean> enabled){
    Set<String> urls=new HashSet<>();
    for(String id:HOT_SOURCES)if(Boolean.TRUE.equals(enabled.get(id)))for(Article a:hotItems(id,snapshots.get(id)))if(!a.url.isEmpty()&&isAIOrTech(a))urls.add(a.url);
    return urls;
  }
  public static List<Article> hotItems(String source,Snapshot snapshot,boolean ai,Set<String> aiURLs){
    List<Article> items=hotItems(source,snapshot);items.removeIf(a->(isAIOrTech(a)||aiURLs.contains(a.url))!=ai);return items;
  }
  public static boolean officialAI(Source s){return "ai".equals(s.category)&&"official".equals(s.authority);}
  public static boolean shouldAutoCheck(Source s,Snapshot snapshot,long now){
    if(snapshot==null)return true;
    if(!snapshot.error.isEmpty()&&now-snapshot.checked<60000)return false;
    long interval=officialAI(s)?86400000L:s.interval*1000L;
    return snapshot.fetched<=0||now-snapshot.fetched>=interval;
  }
  public Snapshot toolSnapshot(JSONObject tool,Map<String,Snapshot> snapshots){
    String id=tool.optString("sourceId");Source s=sourceMap.get(id);Snapshot snapshot=snapshots.get(id);
    return s!=null&&officialAI(s)&&snapshot!=null&&id.equals(snapshot.source)?snapshot:null;
  }
  public static Article latestItem(Snapshot snapshot){
    Article latest=null;if(snapshot!=null)for(Article a:snapshot.items)if(latest==null||a.published>latest.published||a.published==latest.published&&a.rank<latest.rank)latest=a;
    return latest;
  }
  private static final Pattern DATE=Pattern.compile("\\d{4}-\\d{2}-\\d{2}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},\\s+\\d{4}",FLAGS);
  public static final class Source {
    public final String id,name,category,provider,kind,home,color,icon,authority;
    public final boolean defaultEnabled,experimental;
    public final int interval;
    public final List<String> endpoints=new ArrayList<>();
    public final Set<String> hosts=new HashSet<>();
    public Source(JSONObject j){id=j.optString("id");name=j.optString("name");category=j.optString("category");provider=j.optString("provider");kind=j.optString("kind");authority=j.optString("authority");home=j.optString("home");color=j.optString("color","#315b45");icon=j.optString("icon","览");defaultEnabled=j.optBoolean("enabled");experimental=j.optBoolean("experimental");interval=Math.max(60,j.optInt("interval",300));JSONArray a=j.optJSONArray("endpoints");if(a!=null)for(int i=0;i<a.length();i++){String s=a.optString(i);endpoints.add(s);addHost(s);}addHost(home);}
    private void addHost(String s){try{String h=new URI(s).getHost();if(h!=null){hosts.add(h);if(h.startsWith("www."))hosts.add(h.substring(4));}}catch(Exception ignored){}}
  }
  public static final class Article {
    public String id="",source="",sourceName="",category="",provider="",title="",url="",summary="",heat="";
    public long published,fetched;public boolean dateOnly;public int rank;
    public JSONObject json(){JSONObject j=new JSONObject();try{j.put("id",id).put("source",source).put("sourceName",sourceName).put("category",category).put("provider",provider).put("title",title).put("url",url).put("summary",summary).put("heat",heat).put("published",published).put("fetched",fetched).put("dateOnly",dateOnly).put("rank",rank);}catch(Exception ignored){}return j;}
    public static Article from(JSONObject j){if(j==null||publicURL(j.optString("url"))==null||!(j.opt("title") instanceof String)||j.optString("title").isEmpty())return null;Article a=new Article();a.id=bounded(j.optString("id"),128);a.source=bounded(j.optString("source"),100);a.sourceName=bounded(j.optString("sourceName"),100);a.category=bounded(j.optString("category"),40);a.provider=bounded(j.optString("provider"),80);a.title=bounded(j.optString("title"),600);a.url=publicURL(j.optString("url"));a.summary=bounded(j.optString("summary"),1200);a.heat=bounded(j.optString("heat"),80);a.published=Math.max(0,j.optLong("published"));a.fetched=Math.max(0,j.optLong("fetched"));a.dateOnly=j.optBoolean("dateOnly");a.rank=Math.max(1,Math.min(9999,j.optInt("rank",1)));return a.id.isEmpty()?null:a;}
  }
  public static final class Snapshot {
    public final String source;public final List<Article> items=new ArrayList<>();
    public long fetched,checked;public String error="",channel="";public boolean cached;
    public Snapshot(String id){source=id;}
    public JSONObject json(){JSONObject j=new JSONObject();JSONArray a=new JSONArray();for(Article x:items)a.put(x.json());try{j.put("source",source).put("items",a).put("fetched",fetched).put("checked",checked).put("error",error).put("channel",channel);}catch(Exception ignored){}return j;}
    public static Snapshot from(String id,JSONObject j){Snapshot s=new Snapshot(id);if(j==null)return s;s.fetched=Math.max(0,j.optLong("fetched"));s.checked=Math.max(0,j.optLong("checked"));s.error=bounded(j.optString("error"),200);s.channel=bounded(j.optString("channel"),80);JSONArray a=j.optJSONArray("items");if(a!=null)for(int i=0;i<Math.min("linuxdo".equals(id)?LINUXDO_LIMIT:40,a.length());i++){Article x=Article.from(a.optJSONObject(i));if(x!=null&&id.equals(x.source))s.items.add(x);}s.cached=true;return s;}
    Snapshot copy(){Snapshot s=new Snapshot(source);s.items.addAll(items);s.fetched=fetched;s.checked=checked;s.error=error;s.channel=channel;s.cached=true;return s;}
  }
  private static final class Raw {String title="",url="",summary="",heat="";Object date;boolean dateOnly;Raw(String t,String u){title=t;url=u;}}
  public final List<Source> sources=new ArrayList<>();
  public final Map<String,Source> sourceMap=new LinkedHashMap<>();
  public interface Transport {String get(Source source,String endpoint)throws Exception;}
  private final Transport transport;
  public NewsEngine(String config)throws Exception{this(config,NewsEngine::download);}
  public NewsEngine(String config,Transport transport)throws Exception{this.transport=Objects.requireNonNull(transport);JSONArray a=new JSONArray(config);if(a.length()>100)throw new IOException("来源配置过大");for(int i=0;i<a.length();i++){Source s=new Source(a.getJSONObject(i));sources.add(s);sourceMap.put(s.id,s);}}
  public Snapshot fetch(Source source,Snapshot previous,boolean force){
    return fetchInternal(source,previous,force,false);
  }
  public Snapshot recheck(Source source,Snapshot previous){return fetchInternal(source,previous,true,true);}
  /** Manual linux.do refresh. Always bypasses the ordinary cache/cooldown and keeps the prior snapshot on failure. */
  public Snapshot fetchLinuxDo(Source source,Snapshot previous,int mode){
    long now=System.currentTimeMillis();
    String endpoint,channel;
    if(mode==LINUXDO_MODE_VIEWS){endpoint=LINUXDO_VIEWS_ENDPOINT;channel="手机 ECH 直连 · 浏览量（近90天，已过滤 Neo）";}
    else if(mode==LINUXDO_MODE_LATEST){endpoint=LINUXDO_LATEST_ENDPOINT;channel="手机 ECH 直连 · 最新 50";}
    else{endpoint=source.endpoints.get(0);channel="手机 ECH 直连 · 官方公开 RSS";}
    try{
      String body=transport.get(source,endpoint);
      List<Article> items=mode==LINUXDO_MODE_VIEWS?parseLinuxViews(source,body,now)
        :mode==LINUXDO_MODE_LATEST?parseLinuxLatest(source,body,now):parse(source,body,now);
      Snapshot s=new Snapshot(source.id);s.items.addAll(items);s.fetched=s.checked=now;s.channel=channel;return s;
    }catch(Exception e){
      Snapshot failed=previous==null?new Snapshot(source.id):previous.copy();
      failed.checked=now;failed.error=error(e);return failed;
    }
  }
  /** Filter the quarterly candidates, then sort by the same-site view count before taking 50. */
  private List<Article> parseLinuxViews(Source s,String body,long now)throws Exception{
    if(body.getBytes(StandardCharsets.UTF_8).length>MAX_BODY)throw new IOException("响应超过 5 MB");
    validateJsonDepth(body);
    JSONObject root=new JSONObject(body);
    Map<Long,String> users=new HashMap<>();
    JSONArray ua=arr(root,"users");
    for(int i=0;i<ua.length();i++){JSONObject u=ua.optJSONObject(i);if(u!=null)users.put(u.optLong("id"),u.optString("username"));}
    JSONArray data=arr(obj(root,"topic_list"),"topics");
    long cutoff=now-LINUXDO_WINDOW_MS;
    List<JSONObject> candidates=new ArrayList<>();
    for(int i=0;i<data.length();i++){
      JSONObject j=data.optJSONObject(i);if(j==null)continue;
      long poster=opUserId(j);
      String username=users.getOrDefault(poster,"").toLowerCase(Locale.ROOT);
      if(poster==1||LINUXDO_SYSTEM_USER.equals(username))continue;
      long published=date(j.opt("created_at"));
      if(published<=0||published<cutoff||published>now+86400000L||j.optLong("id")<=0
          ||!(j.opt("views") instanceof Number)||j.optLong("views")<0)continue;
      candidates.add(j);
    }
    candidates.sort((a,b)->{int order=Long.compare(b.optLong("views"),a.optLong("views"));
      if(order==0)order=Long.compare(date(b.opt("created_at")),date(a.opt("created_at")));
      return order!=0?order:Long.compare(b.optLong("id"),a.optLong("id"));});
    List<Article> result=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(JSONObject j:candidates){
      String url=publicURL(s.home+"/t/topic/"+j.optLong("id"));
      if(url==null||!seen.add(url))continue;
      String title=clean(first(j.optString("fancy_title"),j.optString("title")));
      if(title.length()<2)continue;
      Article a=new Article();
      a.source=s.id;a.sourceName=s.name;a.category=s.category;a.provider=s.provider;
      a.url=url;a.title=bounded(title,600);
      a.summary=bounded(clean(j.optString("excerpt")),300);
      a.heat=bounded(j.optLong("views")+" 浏览",80);
      a.published=date(j.opt("created_at"));a.fetched=now;a.rank=result.size()+1;a.id=articleId(s.id,url);
      result.add(a);if(result.size()>=LINUXDO_LIMIT)break;
    }
    if(result.isEmpty())throw new IOException("过滤后没有符合条件的条目，可能需要登录或接口已变化");
    return result;
  }
  private List<Article> parseLinuxLatest(Source s,String body,long now)throws Exception{
    if(body.getBytes(StandardCharsets.UTF_8).length>MAX_BODY)throw new IOException("响应超过 5 MB");
    validateJsonDepth(body);
    JSONObject root=new JSONObject(body);
    JSONArray data=arr(obj(root,"topic_list"),"topics");
    List<JSONObject> topics=new ArrayList<>();
    for(int i=0;i<data.length();i++){JSONObject j=data.optJSONObject(i);if(j!=null)topics.add(j);}
    topics.sort((a,b)->Long.compare(date(b.opt("created_at")),date(a.opt("created_at"))));
    List<Article> result=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(JSONObject j:topics){
      String url=publicURL(s.home+"/t/topic/"+j.optLong("id"));
      if(url==null||!seen.add(url))continue;
      String title=clean(first(j.optString("fancy_title"),j.optString("title")));
      if(title.length()<2)continue;
      Article a=new Article();
      a.source=s.id;a.sourceName=s.name;a.category=s.category;a.provider=s.provider;
      a.url=url;a.title=bounded(title,600);
      a.summary=bounded(clean(j.optString("excerpt")),300);
      a.heat=bounded(j.optLong("views")+" 浏览",80);
      a.published=date(j.opt("created_at"));a.fetched=now;a.rank=result.size()+1;a.id=articleId(s.id,url);
      result.add(a);if(result.size()>=LINUXDO_LIMIT)break;
    }
    if(result.isEmpty())throw new IOException("未返回有效条目，可能需要登录或接口已变化");
    return result;
  }
  public String fetchLinuxTopic(Source s,long topicId)throws Exception{
    String endpoint="https://linux.do/t/"+topicId+".json";
    String body=transport.get(s,endpoint);
    if(body.getBytes(StandardCharsets.UTF_8).length>MAX_BODY)throw new IOException("响应超过 5 MB");
    validateJsonDepth(body);
    JSONArray posts=arr(obj(new JSONObject(body),"post_stream"),"posts");
    String cooked="";
    for(int i=0;i<posts.length();i++){JSONObject p=posts.optJSONObject(i);
      if(p!=null&&p.optInt("post_number")==1&&p.optInt("post_type")==1){cooked=p.optString("cooked");break;}}
    if(cooked.isEmpty()&&posts.optJSONObject(0)!=null)cooked=posts.optJSONObject(0).optString("cooked");
    if(cooked.isEmpty())throw new IOException("未返回帖子正文");
    return textFromCooked(cooked);
  }
  private long opUserId(JSONObject topic){
    JSONArray posters=arr(topic,"posters");
    for(int i=0;i<posters.length();i++){JSONObject p=posters.optJSONObject(i);
      if(p!=null){String d=p.optString("description");if(d.contains("原始发帖人")||d.contains("Original Poster"))return p.optLong("user_id");}}
    return posters.optJSONObject(0)!=null?posters.optJSONObject(0).optLong("user_id"):-1;
  }
  private static String articleId(String sourceId,String url)throws Exception{
    byte[] hash=MessageDigest.getInstance("SHA-256").digest((sourceId+"|"+url).getBytes(StandardCharsets.UTF_8));
    StringBuilder id=new StringBuilder();for(int k=0;k<12;k++)id.append(String.format(Locale.ROOT,"%02x",hash[k]&255));
    return id.toString();
  }
  private static String textFromCooked(String cooked){
    // Strip real markup before decoding: encoded code such as <stdio.h> is text.
    String t=cooked
      .replaceAll("(?s)<!--[\\s\\S]*?-->","")
      .replaceAll("(?is)<(?:script|style)\\b[^>]*>[\\s\\S]*?</(?:script|style)>"," ")
      .replaceAll("(?is)<br\\s*/?>","\n")
      .replaceAll("(?is)<li[^>]*>","\n· ")
      .replaceAll("(?is)</(p|div|li|h[1-6]|blockquote|tr|pre|section|article)>","\n")
      .replaceAll("(?s)<(?:\"[^\"]*\"|'[^']*'|[^'\">])*>","");
    t=decode(t);
    StringBuilder out=new StringBuilder();
    for(String line:t.split("\n",-1)){
      String c=line.replaceAll("\\s+"," ").trim();
      if(!c.isEmpty())out.append(c).append('\n');
    }
    return out.toString().trim();
  }
  private Snapshot fetchInternal(Source source,Snapshot previous,boolean force,boolean recheck){
    long now=System.currentTimeMillis();
    if(!recheck&&previous!=null&&((!previous.error.isEmpty()&&now-previous.checked<60000)||(previous.fetched>0&&now-previous.fetched<(force?60:source.interval)*1000L)))return previous.copy();
    List<String> errors=new ArrayList<>();
    for(int i=0;i<source.endpoints.size();i++)try{
      String body=transport.get(source,source.endpoints.get(i));List<Article> items=parse(source,body,System.currentTimeMillis());Snapshot s=new Snapshot(source.id);s.items.addAll(items);s.fetched=s.checked=System.currentTimeMillis();s.channel="linuxdo".equals(source.id)?"手机 ECH 直连 · 官方公开 RSS":i==0?"手机直连 · 主渠道":"手机直连 · 同平台备用";return s;
    }catch(Exception e){String error=error(e);if(!errors.contains(error))errors.add(error);}
    Snapshot failed=previous==null?new Snapshot(source.id):previous.copy();failed.checked=System.currentTimeMillis();failed.error=bounded(String.join("；",errors),200);return failed;
  }
  public static String publicURL(String raw){
    if(raw==null||raw.length()>2048||Pattern.compile("[\\x00-\\x1f\\x7f]").matcher(raw).find())return null;
    try{String candidate=decode(raw.trim());URI u;try{u=new URI(candidate);}catch(URISyntaxException e){u=new URI(candidate.replace("|","%7C"));}String protocol=u.getScheme(),host=u.getHost();if(!("https".equalsIgnoreCase(protocol)||"http".equalsIgnoreCase(protocol))||host==null||u.getUserInfo()!=null)return null;host=host.toLowerCase(Locale.ROOT).replaceAll("\\.$","");if(!host.contains(".")||!host.matches("[a-z0-9.-]+")||host.matches("[0-9.]+")||host.matches(".*(?:^|\\.)(?:localhost|local|localdomain|internal|intranet|lan|home|arpa|onion)$"))return null;int port=u.getPort();if(port!=-1&&!(protocol.equalsIgnoreCase("https")&&port==443)&&!(protocol.equalsIgnoreCase("http")&&port==80))return null;String path=u.getPath();if(path!=null&&path.toLowerCase(Locale.ROOT).matches(".*\\.(?:exe|msi|msix|apk|aab|bat|cmd|ps1|vbs|scr|dll|com|jar|sh)$"))return null;return u.toASCIIString();}catch(Exception ignored){return null;}
  }
  static String download(Source s,String endpoint)throws Exception{
    String current=endpoint;long deadline=System.nanoTime()+18000000000L;
    for(int hop=0;hop<=3;hop++){
      String safe=publicURL(current);if(safe==null||!safe.startsWith("https:")||!s.hosts.contains(new URI(safe).getHost()))throw new IOException("已阻止来源跳转到非登记主机");
      URL target=new URL(safe);HttpsURLConnection c=(HttpsURLConnection)target.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setUseCaches(false);c.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; Yilan-Standalone/3.0; news reader)");c.setRequestProperty("Accept","application/rss+xml,application/atom+xml,application/json,text/html;q=0.8,*/*;q=0.5");c.setRequestProperty("Accept-Encoding","gzip");
      // Weibo's anonymous ajax endpoints return 403 unless the request looks like a same-origin browser call.
      if("weibo".equals(s.id)){
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        c.setRequestProperty("Accept","application/json, text/plain, */*");
        c.setRequestProperty("Referer","https://weibo.com/");
        c.setRequestProperty("sec-fetch-dest","empty");
        c.setRequestProperty("sec-fetch-mode","cors");
        c.setRequestProperty("sec-fetch-site","same-origin");
      }
      try{int code=c.getResponseCode();if(Arrays.asList(301,302,303,307,308).contains(code)){String location=c.getHeaderField("Location");if(location==null)throw new IOException("重定向缺少地址");current=new URI(safe).resolve(location).toString();continue;}if(code<200||code>=300)throw new IOException("HTTP "+code);if(c.getContentLengthLong()>MAX_BODY)throw new IOException("响应超过 5 MB");InputStream stream=c.getInputStream();if("gzip".equalsIgnoreCase(c.getContentEncoding()))stream=new GZIPInputStream(stream);try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n,total=0;while((n=input.read(block))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("更新已停止");if(System.nanoTime()>deadline)throw new IOException("来源响应超时");total+=n;if(total>MAX_BODY)throw new IOException("响应超过 5 MB");output.write(block,0,n);}String charset="UTF-8",type=c.getContentType();if(type!=null){Matcher m=Pattern.compile("charset=([\\w-]+)",FLAGS).matcher(type);if(m.find())charset=m.group(1);}try{return output.toString(charset);}catch(Exception ignored){return output.toString("UTF-8");}}}finally{c.disconnect();}
    }
    throw new IOException("重定向超过限制");
  }
  public static String error(Exception e){String s=String.valueOf(e.getMessage());if(e instanceof java.net.SocketTimeoutException||s.toLowerCase(Locale.ROOT).contains("timed out"))return "来源响应超时";if(e instanceof javax.net.ssl.SSLException)return "证书或加密连接验证失败";if(e instanceof java.net.UnknownHostException)return "无法解析来源地址，请检查手机网络";if(e instanceof org.json.JSONException)return "返回内容不是有效数据，可能需要访问验证";return bounded(s,180);}
  public static String bounded(String s,int max){return s==null?"":s.length()>max?s.substring(0,max)+"…":s;}
  public static String decode(String value){if(value==null)return "";Map<String,String> entities=new HashMap<>();entities.put("amp","&");entities.put("lt","<");entities.put("gt",">");entities.put("quot","\"");entities.put("apos","'");entities.put("nbsp"," ");Matcher m=Pattern.compile("&(#x[0-9a-f]+|#\\d+|amp|lt|gt|quot|apos|nbsp);",FLAGS).matcher(value);StringBuffer out=new StringBuffer();while(m.find()){String k=m.group(1).toLowerCase(Locale.ROOT),v=entities.get(k);if(k.startsWith("#"))try{int n=k.startsWith("#x")?Integer.parseInt(k.substring(2),16):Integer.parseInt(k.substring(1));v=Character.isValidCodePoint(n)&&!(n>=0xd800&&n<=0xdfff)?new String(Character.toChars(n)):"";}catch(Exception ignored){v="";}m.appendReplacement(out,Matcher.quoteReplacement(v==null?m.group():v));}m.appendTail(out);return out.toString();}
  public static String clean(String raw){return decode(raw==null?"":raw.replaceAll("(?s)<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>","$1")).replaceAll("(?is)<(?:script|style)\\b[^>]*>[\\s\\S]*?</(?:script|style)>"," ").replaceAll("(?s)<[^>]*>"," ").replaceAll("\\s+"," ").trim();}
  private static String cdata(String value){String v=value.trim();return v.startsWith("<![CDATA[")&&v.endsWith("]]>")?v.substring(9,v.length()-3):v;}
  private static String tag(String text,String name){Matcher m=Pattern.compile("<"+Pattern.quote(name)+"\\b[^>]*>([\\s\\S]*?)</"+Pattern.quote(name)+"\\s*>",FLAGS).matcher(text);return m.find()?m.group(1):"";}
  private static String attr(String text,String name){Matcher m=Pattern.compile("\\b"+name+"=[\"']([^\"']*)[\"']",FLAGS).matcher(text);return m.find()?m.group(1):"";}
  private static String first(String... text){for(String s:text)if(s!=null&&!s.isEmpty())return s;return "";}
  public static long date(Object raw){if(raw==null||raw==JSONObject.NULL)return 0;String s=String.valueOf(raw).trim();try{if(s.matches("\\d{10,13}"))return Long.parseLong(s)*(s.length()==10?1000L:1L);}catch(Exception ignored){}try{return Instant.parse(s).toEpochMilli();}catch(Exception ignored){}try{return OffsetDateTime.parse(s).toInstant().toEpochMilli();}catch(Exception ignored){}try{return ZonedDateTime.parse(s,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception ignored){}for(String pattern:new String[]{"yyyy-MM-dd","MMM d, yyyy","MMMM d, yyyy"})try{return LocalDate.parse(s,DateTimeFormatter.ofPattern(pattern,Locale.ENGLISH)).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();}catch(Exception ignored){}return 0;}
  private static String htmlDate(String text){Matcher m=DATE.matcher(text);return m.find()?m.group():"";}
  private static List<Raw> rss(String body){List<Raw> out=new ArrayList<>();Matcher items=Pattern.compile("<(item|entry)\\b[^>]*>([\\s\\S]*?)</\\1\\s*>",FLAGS).matcher(body);while(items.find()&&out.size()<300){String b=items.group(2),url=tag(b,"link");if("entry".equalsIgnoreCase(items.group(1))){url="";Matcher links=Pattern.compile("<link\\b([^>]*)/?>",FLAGS).matcher(b);while(links.find()){String rel=attr(links.group(1),"rel");if(rel.isEmpty()||"alternate".equalsIgnoreCase(rel)){url=attr(links.group(1),"href");break;}}}Raw r=new Raw(tag(b,"title"),decode(cdata(url)));r.summary=first(tag(b,"description"),tag(b,"summary"),tag(b,"content"),tag(b,"content:encoded"));r.date=first(tag(b,"pubDate"),tag(b,"published"),tag(b,"updated"),tag(b,"dc:date"));out.add(r);}return out;}
  private static List<Raw> html(Source s,String body){List<Raw> out=new ArrayList<>();String text=body.replaceAll("(?is)<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>","");if("anthropic".equals(s.kind)){Matcher anchors=Pattern.compile("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",FLAGS).matcher(text);while(anchors.find()){if(!anchors.group(1).startsWith("/news/"))continue;String inside=anchors.group(2);Matcher heading=Pattern.compile("<h[1-6]\\b[^>]*>([\\s\\S]*?)</h[1-6]>",FLAGS).matcher(inside);String title=heading.find()?heading.group(1):"";if(title.isEmpty()&&!inside.toLowerCase(Locale.ROOT).contains("<time"))continue;String before=text.substring(Math.max(0,anchors.start()-700),anchors.start()),lastTime="";Matcher times=Pattern.compile("<time\\b[^>]*>([\\s\\S]*?)</time>",FLAGS).matcher(before);while(times.find())lastTime=times.group(1);Raw r=new Raw(title.isEmpty()?inside.replaceAll("(?is)<time\\b[^>]*>.*?</time>",""):title,"https://www.anthropic.com"+anchors.group(1));r.summary=tag(inside,"p");r.date=first(htmlDate(clean(inside)),htmlDate(clean(lastTime)));r.dateOnly=!String.valueOf(r.date).isEmpty();out.add(r);}}else{List<int[]> offsets=new ArrayList<>();List<String[]> heads=new ArrayList<>();Matcher h=Pattern.compile("<h2\\b([^>]*)>([\\s\\S]*?)</h2>",FLAGS).matcher(text);while(h.find()){offsets.add(new int[]{h.start(),h.end()});heads.add(new String[]{h.group(1),clean(h.group(2))});}boolean z="zcode".equals(s.kind);for(int i=0;i<heads.size();i++){String title=heads.get(i)[1];if(z?!title.matches("Release\\s+v[\\w.-]+" ):!title.matches("Date:\\s*\\d{4}-\\d{2}-\\d{2}.*"))continue;int end=i+1<offsets.size()?offsets.get(i+1)[0]:text.length();if(z){end=text.length();for(int j=i+1;j<heads.size();j++)if(heads.get(j)[1].matches("Release\\s+v[\\w.-]+")){end=offsets.get(j)[0];break;}}String content=text.substring(offsets.get(i)[1],end),before=clean(text.substring(Math.max(0,offsets.get(i)[0]-5000),offsets.get(i)[0]));String publish="";Matcher dates=DATE.matcher(before);while(dates.find())publish=dates.group();String id=attr(heads.get(i)[0],"id");if(id.isEmpty())id=title.replaceFirst("^Release\\s+","");Matcher sub=Pattern.compile("<h[1-6]\\b[^>]*>([\\s\\S]*?)</h[1-6]>",FLAGS).matcher(content);Raw r=new Raw(z?"ZCode "+title.replaceFirst("^Release\\s+",""):sub.find()?sub.group(1):"DeepSeek 官方更新 · "+title,s.home+"#"+id);r.summary=z?content:tag(content,"p");r.date=z?publish:htmlDate(title);r.dateOnly=!String.valueOf(r.date).isEmpty();out.add(r);}}return out;}
  private static JSONObject obj(JSONObject j,String key){JSONObject o=j==null?null:j.optJSONObject(key);return o==null?new JSONObject():o;}
  private static JSONArray arr(JSONObject j,String key){JSONArray a=j==null?null:j.optJSONArray(key);return a==null?new JSONArray():a;}
  private static String val(JSONObject j,String k){Object v=j.opt(k);return v==null||v==JSONObject.NULL?"":String.valueOf(v);}
  private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return "";}}
  private static List<Raw> rows(Source s,String body)throws Exception{
    if("rss".equals(s.kind)||"rss-ai".equals(s.kind)){List<Raw> r=rss(body);if("rss-ai".equals(s.kind))r.removeIf(x->!aiTopic(bounded(clean(x.title),600),bounded(clean(x.summary),300)));return r;}
    if("releases".equals(s.kind)&&body.trim().startsWith("<"))return rss(body);
    if(Arrays.asList("anthropic","deepseek","zcode").contains(s.kind))return html(s,body);
    if("baidu".equals(s.id)){Matcher data=Pattern.compile("<!--s-data:([\\s\\S]*?)-->",FLAGS).matcher(body);if(!data.find())throw new IOException("页面结构变化或需要访问验证");body=data.group(1);}
    validateJsonDepth(body);JSONArray data;JSONObject root=body.trim().startsWith("[")?new JSONObject():new JSONObject(body);List<Raw> out=new ArrayList<>();
    if("releases".equals(s.kind)){data=new JSONArray(body);for(int i=0;i<data.length();i++){JSONObject j=data.optJSONObject(i);if(j==null||j.optBoolean("draft"))continue;Raw r=new Raw(first(val(j,"name"),val(j,"tag_name")),val(j,"html_url"));r.summary=val(j,"body");r.date=j.opt("published_at");r.heat=j.optBoolean("prerelease")?"预发布":"";out.add(r);}return out;}
    switch(s.id){case "xinhuanet":data=arr(root,"datasource");break;case "baidu":JSONObject cards=arr(obj(root,"data"),"cards").optJSONObject(0);data=arr(cards,"content");break;case "toutiao":case "zhihu":data=arr(root,"data");break;case "thepaper":data=arr(obj(root,"data"),"hotNews");break;case "wallstreetcn":data=arr(obj(root,"data"),"items");break;case "v2ex":data=new JSONArray(body);break;case "tieba":data=arr(obj(obj(root,"data"),"bang_topic"),"topic_list");break;case "hackernews":data=arr(root,"hits");break;case "weibo":JSONObject d=obj(root,"data");data=d.optJSONArray("realtime");if(data==null)data=arr(d,"band_list");break;case "bilibili":data=arr(root,"list");break;case "bilibili-video":data=arr(obj(root,"data"),"list");break;case "douyin":data=arr(root,"word_list");break;default:throw new IOException("尚未支持此来源");}
    for(int i=0;i<Math.min(300,data.length());i++){JSONObject j=data.optJSONObject(i);if(j==null)continue;Raw r=new Raw("","");switch(s.id){case "xinhuanet":r.title=val(j,"title");r.url=val(j,"publishUrl");r.summary=val(j,"summary");String time=val(j,"publishTime");try{r.date=java.time.LocalDateTime.parse(time,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString();}catch(Exception ignored){}break;case "baidu":if(j.optBoolean("isTop"))continue;r.title=val(j,"word");r.url=val(j,"rawUrl");r.summary=val(j,"desc");r.heat=val(j,"hotScore");break;case "toutiao":r.title=val(j,"Title");r.url="https://www.toutiao.com/trending/"+val(j,"ClusterIdStr")+"/";r.heat=val(j,"HotValue");break;case "thepaper":r.title=val(j,"name");r.url="https://www.thepaper.cn/newsDetail_forward_"+val(j,"contId");r.date=j.opt("pubTimeLong");break;case "wallstreetcn":r.title=first(val(j,"title"),val(j,"content_text"));r.url=val(j,"uri");r.summary=val(j,"content_text");r.date=j.opt("display_time");break;case "v2ex":r.title=val(j,"title");r.url=val(j,"url");r.summary=val(j,"content");r.date=j.opt("created");r.heat=val(j,"replies")+" 回复";break;case "tieba":r.title=val(j,"topic_name");r.url=val(j,"topic_url");r.date=j.opt("create_time");r.heat=val(j,"discuss_num");break;case "hackernews":r.title=val(j,"title");r.url="https://news.ycombinator.com/item?id="+val(j,"objectID");r.date=j.opt("created_at");r.heat=val(j,"points")+" points";break;case "weibo":r.title=first(val(j,"word"),val(j,"note"));r.url="https://s.weibo.com/weibo?q="+enc(r.title);r.heat=val(j,"num");break;case "zhihu":JSONObject t=obj(j,"target");r.title=val(obj(t,"title_area"),"text");r.url=val(obj(t,"link"),"url");r.summary=val(obj(t,"excerpt_area"),"text");r.heat=val(obj(t,"metrics_area"),"text");break;case "bilibili":r.title=first(val(j,"show_name"),val(j,"keyword"));r.url="https://search.bilibili.com/all?keyword="+enc(val(j,"keyword"));break;case "bilibili-video":r.title=val(j,"title");r.url="https://www.bilibili.com/video/"+val(j,"bvid");r.summary=val(j,"desc");r.date=j.opt("pubdate");String views=val(obj(j,"stat"),"view");if(!views.isEmpty())r.heat=views+" 播放";break;case "douyin":r.title=val(j,"word");r.url="https://www.douyin.com/search/"+enc(val(j,"word")).replace("+","%20");r.heat=val(j,"hot_value");break;}out.add(r);}return out;
  }
  private static void validateJsonDepth(String body)throws IOException{int depth=0;boolean string=false,escaped=false;for(int i=0;i<body.length();i++){char c=body.charAt(i);if(string){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c=='{'||c=='['){if(++depth>64)throw new IOException("来源数据嵌套过深");}else if(c=='}'||c==']')depth--;}}
  public static List<Article> parse(Source s,String body,long now)throws Exception{
    if(body.getBytes(StandardCharsets.UTF_8).length>MAX_BODY)throw new IOException("响应超过 5 MB");
    List<Article> result=new ArrayList<>();Set<String> seen=new HashSet<>();long newest=0;
    for(Raw raw:rows(s,body)){String url=publicURL(raw.url),title=clean(raw.title);if(url==null||title.length()<2||!seen.add(url))continue;Article a=new Article();a.source=s.id;a.sourceName=s.name;a.category=s.category;a.provider=s.provider;a.url=url;a.title=bounded(title,600);a.summary=bounded(clean(raw.summary),300);a.heat=bounded(clean(raw.heat),80);a.dateOnly=raw.dateOnly;a.published=date(raw.date);a.fetched=now;a.rank=result.size()+1;byte[] hash=MessageDigest.getInstance("SHA-256").digest((s.id+"|"+url).getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder();for(int i=0;i<12;i++)id.append(String.format(Locale.ROOT,"%02x",hash[i]&255));a.id=id.toString();result.add(a);newest=Math.max(newest,a.published);if(result.size()>=40)break;}
    if(result.isEmpty())throw new IOException("未返回有效条目，可能需要登录或接口已变化");
    if("rss".equals(s.kind)&&!"ai".equals(s.category)&&newest>0&&now-newest>7L*86400000)throw new IOException("来源疑似停更，已保留历史快照");if(newest>now+86400000L)throw new IOException("上游发布时间异常");return result;
  }
}
