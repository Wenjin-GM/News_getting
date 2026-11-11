package cn.yilan.news;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
public final class NativeEngineTest {
  private static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
  private static void rejects(NewsEngine.Source s,String input,long now)throws Exception{boolean failed=false;try{NewsEngine.parse(s,input,now);}catch(Exception e){failed=true;}check(failed,"Expected rejected feed");}
  private static double luminance(int color){double out=0;double[] weights={0.2126,0.7152,0.0722};for(int i=0;i<3;i++){double channel=((color>>(16-i*8))&255)/255.0;out+=weights[i]*(channel<=0.04045?channel/12.92:Math.pow((channel+0.055)/1.055,2.4));}return out;}
  private static double contrast(int foreground,int background){double a=luminance(foreground),b=luminance(background);return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);}
  private static int over(int foreground,int background){double alpha=((foreground>>>24)&255)/255.0;int color=0xff000000;for(int shift:new int[]{16,8,0})color|=((int)Math.round(((foreground>>>shift)&255)*alpha+((background>>>shift)&255)*(1-alpha)))<<shift;return color;}
  private static void glassThemes(){
    Set<String> scenes=new HashSet<>();for(int page=0;page<3;page++)check(!ReaderTheme.scene(page).isEmpty()&&scenes.add(ReaderTheme.scene(page)),"Reading pages have distinct bundled scenes");check(ReaderTheme.scene(3).isEmpty(),"Settings page has no picture");
    for(String mode:new String[]{"day","night"}){
      ReaderTheme p=ReaderTheme.forMode(mode);
      for(int image:new int[]{0xff000000,0xffffffff})for(int scrim:new int[]{p.scrimTop(),p.scrimBottom()}){
        int background=over(scrim,image);check(contrast(p.ink,background)>=4.5,"Unboxed filters readable over image extreme: "+mode);
        for(int fill:new int[]{p.glassTop(false),p.glassBottom(false)}){int card=over(fill,background);check(contrast(p.ink,card)>=4.5&&contrast(p.muted,card)>=4.5&&contrast(p.accent,card)>=4.5,"Glass title/metadata/action contrast over image extreme: "+mode);}
        for(int fill:new int[]{p.glassTop(true),p.glassBottom(true)})check(contrast(p.onAccent,over(fill,background))>=4.5,"Translucent primary action contrast: "+mode);
      }
      check((p.glassTop(false)>>>24)<255&&(p.glassBottom(false)>>>24)<255,"Reading cards retain actual transparency");
    }
    System.out.println("PASS: distinct reading backgrounds, settings exclusion, genuine translucent fills and layered text contrast on black/white image extremes");
  }
  private static void themePreferences(NewsEngine engine)throws Exception{
    NewsEngine.Article a=report("chinanews","日夜切换保留原收藏",1,"theme-favorite");NewsEngine.Snapshot feed=new NewsEngine.Snapshot(a.source);feed.items.add(a);feed.fetched=1;
    JSONObject legacy=new JSONObject().put("schema",3).put("favorites",new JSONArray().put(a.json())).put("read",new JSONArray().put(a.id)).put("feeds",new JSONObject().put(a.source,feed.json())).put("enabled",new JSONObject().put(a.source,false)).put("large",true);
    NewsState state=new NewsState();state.restore(engine,legacy);check(state.themeMode.equals("day"),"Pre-theme installations default to day without migration");
    for(String mode:new String[]{"night","day","night"}){
      state.themeMode=mode;NewsState next=new NewsState();next.restore(engine,new JSONObject(state.json().toString()));
      check(next.themeMode.equals(mode)&&next.large,"Theme survives reload with existing reading preference");
      check(next.favorites.get(0).id.equals(a.id)&&next.read.contains(a.id)&&!next.sourceEnabled.get(a.source)&&next.snapshots.get(a.source).items.get(0).id.equals(a.id),"Theme changes preserve favorite/read/source/cache identities");state=next;
    }
    state.restore(engine,new JSONObject(legacy.toString()).put("themeMode","unknown"));check(state.themeMode.equals("day"),"Unknown theme mode has a safe default");
    for(String mode:new String[]{"day","night"}){ReaderTheme p=ReaderTheme.forMode(mode);for(int surface:new int[]{p.surface,p.background,p.raised}){
      check(contrast(p.ink,surface)>=4.5,"Body text contrast: "+mode);check(contrast(p.muted,surface)>=4.5,"Metadata contrast: "+mode);check(contrast(p.accent,surface)>=4.5,"Accent text contrast: "+mode);
    }check(contrast(p.onAccent,p.accent)>=4.5,"Primary button contrast: "+mode);check(contrast(p.accent,p.soft)>=4.5,"Selected tab contrast: "+mode);}
    System.out.println("PASS: day/night persistence, legacy fallback, preserved reading data and WCAG AA text contrast tokens");
  }
  private static void dialogChoiceState(){
    List<String> ids=Arrays.asList("all","news","tech","finance");Set<String> values=new LinkedHashSet<>();boolean[] checked=new boolean[ids.size()];
    ReadingRules.syncChoices(ids,values,checked);check(Arrays.equals(checked,new boolean[]{true,false,false,false}),"Empty selection checks only All on opening");
    // AlertDialog updates only the tapped slot; our callback must update the other slots too.
    checked[1]=true;ReadingRules.choose(values,"news",true);ReadingRules.syncChoices(ids,values,checked);
    check(Arrays.equals(checked,new boolean[]{false,true,false,false}),"Backing array must not restore All when News rows rebind");
    checked[2]=true;ReadingRules.choose(values,"tech",true);ReadingRules.syncChoices(ids,values,checked);
    check(Arrays.equals(checked,new boolean[]{false,true,true,false}),"Selecting another specific category preserves both checks");
    checked[0]=true;ReadingRules.choose(values,"all",true);ReadingRules.syncChoices(ids,values,checked);
    check(values.isEmpty()&&Arrays.equals(checked,new boolean[]{true,false,false,false}),"Selecting All clears stale specific flags before rows rebind");
    checked[0]=false;ReadingRules.choose(values,"all",false);ReadingRules.syncChoices(ids,values,checked);
    check(checked[0],"Unchecking All with no concrete category returns to All");
    checked[3]=true;ReadingRules.choose(values,"finance",true);ReadingRules.syncChoices(ids,values,checked);
    checked[3]=false;ReadingRules.choose(values,"finance",false);ReadingRules.syncChoices(ids,values,checked);
    check(values.isEmpty()&&Arrays.equals(checked,new boolean[]{true,false,false,false}),"Removing the last concrete category restores only All");
    Set<String> reopened=new LinkedHashSet<>(Arrays.asList("news","tech"));ReadingRules.syncChoices(ids,reopened,checked);
    check(Arrays.equals(checked,new boolean[]{false,true,true,false}),"Reopening reflects committed multiple categories without stale flags");
    System.out.println("PASS: dialog backing-array synchronization, All/specific exclusivity, multiple checks and reopen state");
  }
  private static void deviceCorrections(NewsEngine engine,String sourceFile)throws Exception{
    dialogChoiceState();
    Set<String> chosen=new LinkedHashSet<>();ReadingRules.choose(chosen,"news",true);
    ReadingRules.choose(chosen,"all",false);
    check(chosen.equals(new LinkedHashSet<>(Arrays.asList("news"))),"Programmatic All uncheck must not erase News");
    ReadingRules.choose(chosen,"tech",true);ReadingRules.choose(chosen,"all",false);
    check(chosen.size()==2&&chosen.contains("news")&&chosen.contains("tech"),"Specific categories remain multi-select after callback synchronization");
    ReadingRules.choose(chosen,"all",true);check(chosen.isEmpty(),"Clicking All clears concrete categories");
    ReadingRules.choose(chosen,"finance",false);check(chosen.isEmpty(),"Empty category set is All");
    JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(sourceFile).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    String original=catalog.toString();Set<String> excluded=ReadingRules.toolSources(catalog);
    for(String id:new String[]{"ai-kimi-code","ai-codex","ai-claude-code","ai-gemini-cli","ai-qwen-code"})check(excluded.contains(id)&&!ReadingRules.platformSource(catalog,engine.sourceMap.get(id)),"Tool source excluded from platform news: "+id);
    for(String id:new String[]{"ai-openai","ai-anthropic","ai-deepseek"})check(ReadingRules.platformSource(catalog,engine.sourceMap.get(id)),"Manufacturer/model news still eligible: "+id);
    JSONObject old=new JSONObject().put("name","Old").put("releasedAt","2026-01-01"),recent=new JSONObject().put("name","Recent").put("releasedAt","2026-10-20"),unknown=new JSONObject().put("name","Unknown").put("releasedAt",JSONObject.NULL);
    JSONArray sourceOrder=new JSONArray().put(unknown).put(old).put(recent);List<JSONObject> ordered=ReadingRules.sortedModels(sourceOrder);
    check(ordered.get(0)==recent&&ordered.get(1)==old&&ordered.get(2)==unknown&&sourceOrder.getJSONObject(0)==unknown,"Models ordered by real release dates without mutating snapshot");
    Map<String,NewsEngine.Snapshot> snapshots=new HashMap<>();long now=Instant.parse("2026-11-09T10:00:00Z").toEpochMilli();
    NewsEngine.Snapshot tool=new NewsEngine.Snapshot("ai-codex");tool.fetched=now+1000;snapshots.put(tool.source,tool);
    check(ReadingRules.latestModelCheck(catalog,engine,snapshots)==0,"Tool-only refresh cannot claim model-news check");
    NewsEngine.Snapshot news=new NewsEngine.Snapshot("ai-openai");news.fetched=now;snapshots.put(news.source,news);
    news.items.add(report(news.source,"OpenAI 发布 GPT-99.9 新模型",now,"unseen-model"));
    tool.items.add(report(tool.source,"Codex 发布 GPT-98.9 新模型支持",now,"tool-release"));
    check(ReadingRules.latestModelCheck(catalog,engine,snapshots)==now,"Recent successful model news check is separate from snapshot date");
    List<NewsEngine.Article> notices=ReadingRules.newModelNotices(catalog,engine,snapshots);
    check(notices.size()==1&&notices.get(0).id.equals(news.items.get(0).id),"Unseen model gets a separate source-linked notice, tool releases do not");
    check(catalog.toString().equals(original)&&catalog.getJSONObject("modelData").getString("asOf").equals("2026-10-21"),"Fresh model release must never rewrite characteristics, claims or date");
    check(ReadingRules.previousDay("2026-11-01").equals("2026-10-31"),"Date continuation crosses month boundary");
    Map<String,Boolean> enabled=new HashMap<>();enabled.put("ithome",true);enabled.put("ifanr",true);
    List<FeaturedNews.Event> events=FeaturedNews.group(Arrays.asList(report("ithome","两家媒体报道同一软件正式发布",now,"timeline-a"),report("ifanr","两家媒体报道同一软件正式发布！",now,"timeline-b")),engine.sourceMap,enabled);
    check(ReadingRules.hasDay(events,"2026-11-09")&&!ReadingRules.hasDay(events,"2026-11-08"),"Date timeline uses only actual event dates, gaps stay empty");
    check(ReadingRules.olderGap(events,now).equals("2026-11-08"),"Exhausted local timeline checks the immediately preceding day, not seven days ago");
    check(ReadingRules.olderGap(Collections.emptyList(),now).equals("2026-11-09"),"Empty timeline first checks today instead of inventing old dates");
    System.out.println("PASS: recursive All-uncheck regression, tool-feed exclusion, model sorting, independent check time and immutable snapshot/new-release notices");
  }
  private static NewsEngine.Article report(String source,String title,long published,String link){
    NewsEngine.Article a=new NewsEngine.Article();a.id=source+"-"+link;a.source=source;a.sourceName=source;a.title=title;a.url="https://example.com/"+link;a.published=published;a.fetched=published;a.rank=1;return a;
  }
  private static void readingFiltersAndAI(NewsEngine engine,String sourceFile)throws Exception{
    Set<String> categories=new LinkedHashSet<>();ReadingRules.choose(categories,"tech",true);ReadingRules.choose(categories,"finance",true);
    check(ReadingRules.category(categories,"tech")&&ReadingRules.category(categories,"finance")&&!ReadingRules.category(categories,"news"),"Category multiselect uses union");
    ReadingRules.choose(categories,"all",true);check(categories.isEmpty()&&ReadingRules.category(categories,"news"),"All clears specific categories");
    ReadingRules.choose(categories,"news",true);ReadingRules.choose(categories,"news",false);check(categories.isEmpty(),"No specific category equals all");
    JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(sourceFile).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    check(ReadingRules.provider(catalog,"moonshot","china",Collections.emptySet())&&!ReadingRules.provider(catalog,"openai","china",Collections.emptySet()),"Region-only filtering");
    Set<String> vendors=new HashSet<>(Arrays.asList("moonshot","alibaba"));check(ReadingRules.provider(catalog,"moonshot","china",vendors)&&ReadingRules.provider(catalog,"alibaba","china",vendors)&&!ReadingRules.provider(catalog,"zai","china",vendors),"Vendor multiselect narrows region");
    NewsEngine.Snapshot board=new NewsEngine.Snapshot("v2ex");board.fetched=1;board.items.add(report("v2ex","开源软件发布消息",1,"board"));
    check(ReadingRules.aiBoard("v2ex",board),"Successful AI board eligible");board.error="offline";check(!ReadingRules.aiBoard("v2ex",board),"Latest failed V2EX hidden, not placeholder");
    board.error="";check(!ReadingRules.aiBoard("toutiao",board)&&!ReadingRules.aiBoard("tieba",board),"AI boards exclude both news stations");
    JSONObject empty=new JSONObject().put("text","Unsupported claim").put("confidence","verified").put("sourceIds",new JSONArray());
    check(ReadingRules.confidence(empty).equals("unverified"),"Empty evidence never verified");
    JSONObject data=catalog.optJSONObject("modelData");check(data!=null&&data.optString("asOf").equals("2026-10-21"),"Snapshot date retained");
    JSONArray profiles=data.getJSONArray("profiles"),models=data.getJSONArray("models");boolean kimi=false,zhipu=false;
    for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);kimi|=p.optString("id").equals("moonshot");zhipu|=p.optString("id").equals("zai");for(String key:new String[]{"strengths","weaknesses"}){JSONArray claims=p.optJSONArray(key);for(int j=0;claims!=null&&j<claims.length();j++){JSONObject c=claims.getJSONObject(j);if(c.optJSONArray("sourceIds")==null||c.getJSONArray("sourceIds").length()==0)check(!ReadingRules.confidence(c).equals("verified"),"Imported empty evidence downgraded");}}}
    check(kimi&&zhipu&&models.length()==42,"Mapped local-site profiles and models imported");
    long time=Instant.parse("2026-11-08T17:00:00Z").toEpochMilli();String date="2026-11-09";
    NewsArchive archive=new NewsArchive();check(ReadingRules.forDay(archive.articles(),date).isEmpty(),"Requested missing day initially empty");
    NewsEngine.Snapshot first=new NewsEngine.Snapshot("ithome"),second=new NewsEngine.Snapshot("ifanr");first.items.add(report("ithome","两家报道同一款开源软件正式发布",time,"date-a"));second.items.add(report("ifanr","两家报道同一款开源软件正式发布！",time+1000,"date-b"));archive.accept(first,engine,time);archive.accept(second,engine,time+1000);
    Map<String,Boolean> enabled=new HashMap<>();enabled.put("ithome",true);enabled.put("ifanr",true);
    check(FeaturedNews.group(ReadingRules.forDay(archive.articles(),date),engine.sourceMap,enabled).size()==1,"Returned target-date records regroup under unchanged rules");
    check(ReadingRules.forDay(archive.articles(),"2026-11-01").isEmpty(),"No invented history when source did not return target day");
    NewsEngine.Snapshot ai=new NewsEngine.Snapshot("ai-codex");ai.items.add(report("ai-codex",first.items.get(0).title,time,"ai-a"));archive.accept(ai,engine,time);
    enabled.put("ai-codex",true);List<FeaturedNews.Event> events=FeaturedNews.group(archive.articles(),engine.sourceMap,enabled,true,Collections.emptySet());check(events.isEmpty(),"AI featured cannot borrow general news for a second source");
    ai=new NewsEngine.Snapshot("ai-media-ithome");ai.items.add(report("ai-media-ithome",first.items.get(0).title,time,"ai-b"));archive.accept(ai,engine,time);enabled.put("ai-media-ithome",true);
    events=FeaturedNews.group(archive.articles(),engine.sourceMap,enabled,true,Collections.emptySet());check(events.size()==1&&events.get(0).reports.size()==2,"AI source archive supports separate AI featured");
    NewsEngine.Source blocked=new NewsEngine.Source(new JSONObject().put("id","ithome").put("endpoints",new JSONArray().put("http://example.com/blocked")));first.fetched=first.checked=System.currentTimeMillis();
    check(engine.fetch(blocked,first,true).error.isEmpty()&&!engine.recheck(blocked,first).error.isEmpty(),"Explicit date recheck bypasses fresh cache but retains transport policy");
    System.out.println("PASS: multiselect/region filters, failed-board hiding, snapshot confidence, target-day recheck and isolated AI featured");
  }
  private static void featuredAndArchive(NewsEngine engine)throws Exception{
    long now=Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();
    Map<String,Boolean> enabled=new HashMap<>();for(String id:engine.sourceMap.keySet())enabled.put(id,true);
    NewsEngine.Article a=report("ithome","某科技公司发布新款智能手机系统",now,"a"),b=report("ifanr","某科技公司发布新款智能手机系统！",now-3600000,"b");
    check(FeaturedNews.group(Arrays.asList(a),engine.sourceMap,enabled).isEmpty(),"Single source stays out of featured");
    List<FeaturedNews.Event> groups=FeaturedNews.group(Arrays.asList(a,b,a),engine.sourceMap,enabled);
    check(groups.size()==1&&groups.get(0).reports.size()==2&&groups.get(0).publishers.size()==2,"Similar reports group once; same source/link is deduplicated");
    check(groups.get(0).reports.get(0).id.equals(a.id),"Grouping preserves original IDs");
    check(!FeaturedNews.similar(a.title,"某科技公司公布季度财务经营报告"),"Shared company alone cannot merge events");
    check(!FeaturedNews.similar("某公司发布新版模型系统版本二号 2","某公司发布新版模型系统版本三号 3"),"Different versions cannot merge by lexical overlap");
    check(FeaturedNews.similar("某科技公司发布新款智能手机系统！",a.title),"Punctuation is normalized");
    b.published=now-FeaturedNews.WINDOW_MS;
    check(FeaturedNews.group(Arrays.asList(a,b),engine.sourceMap,enabled).size()==1,"Exactly 72 hours can group");
    b.published--;
    check(FeaturedNews.group(Arrays.asList(a,b),engine.sourceMap,enabled).isEmpty(),"More than 72 hours cannot group");
    b.published=0;
    check(FeaturedNews.group(Arrays.asList(a,b),engine.sourceMap,enabled).isEmpty(),"Unknown dates cannot establish the 72h condition");
    b.published=now-3600000;
    NewsEngine.Article c=report("chinanews",a.title,now,"c"),d=report("chinanews-world",a.title,now,"d");
    check(FeaturedNews.group(Arrays.asList(c,d),engine.sourceMap,enabled).isEmpty(),"Two channels of one publisher are not independent sources");
    String x="甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉",y="甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳戌亥天地",z="甲乙丙丁戊己庚辛壬癸子丑寅卯宇宙戌亥天地";
    check(FeaturedNews.similar(x,y)&&FeaturedNews.similar(y,z)&&!FeaturedNews.similar(x,z),"Bridge fixture has two similar edges");
    groups=FeaturedNews.group(Arrays.asList(report("ithome",x,now,"x"),report("ifanr",y,now-1000,"y"),report("thepaper",z,now-2000,"z")),engine.sourceMap,enabled);
    check(groups.size()==1&&groups.get(0).reports.size()==2,"No transitive chain merging");
    for(String id:Arrays.asList("v2ex","v2ex-latest","hackernews"))check(!FeaturedNews.latestSource(engine.sourceMap.get(id)),"Community feeds excluded from news latest");
    check(FeaturedNews.stationSource("toutiao")&&FeaturedNews.stationSource("tieba")&&!FeaturedNews.stationSource("v2ex"),"News station lists use only two sources");
    NewsEngine.Snapshot v=new NewsEngine.Snapshot("v2ex");NewsEngine.Article offTopic=report("v2ex","今天吃什么",now,"shared");v.items.add(offTopic);
    check(FeaturedNews.hotItems("v2ex",v,true,new HashSet<>(Arrays.asList(offTopic.url))).isEmpty(),"Off-topic V2EX stays hidden even with cross-site URL overlap");
    groups=FeaturedNews.group(Arrays.asList(a,b),engine.sourceMap,enabled);
    NewsEngine.Article oldA=report("ithome",a.title,now-9L*86400000,"old-a"),oldB=report("ifanr",a.title,now-9L*86400000,"old-b");
    groups.addAll(FeaturedNews.group(Arrays.asList(oldA,oldB),engine.sourceMap,enabled));
    check(FeaturedNews.initialDay(now).equals("2026-11-03"),"Seven Shanghai calendar days");
    check(FeaturedNews.day(Instant.parse("2026-11-08T16:00:00Z").toEpochMilli()).equals("2026-11-09"),"Shanghai midnight grouping");
    check(FeaturedNews.days(groups,FeaturedNews.initialDay(now)).size()==1&&FeaturedNews.days(groups,"2026-10-01").size()==2,"Older pages only expose actual local dates");
    enabled.put("ifanr",false);check(FeaturedNews.group(Arrays.asList(a,b),engine.sourceMap,enabled).isEmpty(),"Disabled source does not supply a second publisher");enabled.put("ifanr",true);
    NewsArchive archive=new NewsArchive();check(archive.articles().isEmpty(),"Upgrade archive starts empty, not from old snapshots");
    NewsEngine.Snapshot snap=new NewsEngine.Snapshot("ithome");snap.items.add(a);snap.fetched=now;
    snap.cached=true;archive.accept(snap,engine,now);check(archive.articles().isEmpty(),"Cached old items do not manufacture history");
    snap.cached=false;archive.accept(snap,engine,now);check(archive.articles().size()==1,"Successful refresh appends actual records");
    snap.items.clear();snap.items.add(report("ithome","另一篇实际抓到的报道",now+1000,"next"));archive.accept(snap,engine,now+1000);
    check(archive.articles().size()==2,"Refreshing a snapshot does not erase archive history");
    snap.error="offline";archive.accept(snap,engine,now+2000);check(archive.articles().size()==2,"Failed refresh cannot clear history");
    NewsArchive reload=new NewsArchive();reload.restore(new JSONObject(archive.json().toString()),engine);
    check(reload.articles().size()==2&&reload.articles().get(0).id.equals(a.id),"Archive round trip retains IDs and records");
    NewsArchive full=new NewsArchive();NewsEngine.Snapshot bulk=new NewsEngine.Snapshot("ithome");
    for(int i=0;i<NewsArchive.MAX_ITEMS;i++)bulk.items.add(report("ithome","已采集的新闻标题",now,"bulk-"+i));
    full.accept(bulk,engine,now);check(full.articles().size()==NewsArchive.MAX_ITEMS,"Archive respects item capacity");
    NewsEngine.Snapshot extra=new NewsEngine.Snapshot("ithome");extra.items.add(report("ithome","新的实际新闻标题",now,"extra"));full.accept(extra,engine,now+1);
    check(full.incomplete&&full.articles().size()==NewsArchive.MAX_ITEMS&&full.articles().get(0).id.equals(bulk.items.get(0).id),"Capacity pressure preserves seven days and reports missed additions");
    full.accept(extra,engine,now+8L*86400000);
    check(full.articles().size()==NewsArchive.MAX_ITEMS&&full.articles().stream().anyMatch(t->t.id.equals(extra.items.get(0).id)),"Old records may be evicted after seven days");
    check(full.json().toString().getBytes(StandardCharsets.UTF_8).length<=NewsArchive.MAX_BYTES,"Serialized archive stays within byte limit");
    System.out.println("PASS: featured lexical grouping, 72h boundaries, publisher dedup, Shanghai dates, source routing and bounded seven-day local archive");
  }
  private static void presentationAndDailyChecks(NewsEngine engine,long now)throws Exception{
    String[] technology={"编程入门","新芯片上市","软件发布","开源项目","手机系统更新","互联网产品","Android 版本","Python 工具","普通标题"};
    for(String id:NewsEngine.HOT_SOURCES){
      NewsEngine.Snapshot snapshot=new NewsEngine.Snapshot(id);
      for(int i=0;i<technology.length;i++){
        NewsEngine.Article a=new NewsEngine.Article();a.id=id+"-"+i;a.source=id;a.title=technology[i];a.rank=i+1;snapshot.items.add(a);
      }
      NewsEngine.Article summary=new NewsEngine.Article();summary.id=id+"-summary";summary.source=id;summary.title="发布会";summary.summary="Claude 新动态";summary.rank=10;snapshot.items.add(summary);
      List<NewsEngine.Article> today=NewsEngine.hotItems(id,snapshot,false),ai=NewsEngine.hotItems(id,snapshot,true);
      Set<String> seen=new HashSet<>();
      for(List<NewsEngine.Article> list:Arrays.asList(today,ai)){
        int rank=0;for(NewsEngine.Article a:list){check(seen.add(a.id),"Hot article cannot appear in both views");check(a.rank>rank,"Routing preserves rank order");rank=a.rank;}
      }
      check(seen.size()==snapshot.items.size(),"Every hot article goes to exactly one view");
      check(ai.contains(summary),"AI keywords in summary route to AI heat");
      check(today.size()==("hackernews".equals(id)?0:1),"Technology titles and all HN articles go to AI heat");
      check(snapshot.items.size()==10,"Routing never removes items from saved snapshot");
    }
    NewsEngine.Source official=engine.sourceMap.get("ai-codex"),media=engine.sourceMap.get("ai-media-ithome");
    NewsEngine.Snapshot hn=new NewsEngine.Snapshot("hackernews"),v2ex=new NewsEngine.Snapshot("v2ex");
    NewsEngine.Article h=new NewsEngine.Article(),v=new NewsEngine.Article();h.source="hackernews";v.source="v2ex";h.url=v.url="https://example.com/shared";h.title=v.title="Same story";hn.items.add(h);v2ex.items.add(v);
    Map<String,NewsEngine.Snapshot> boards=new HashMap<>();boards.put(hn.source,hn);boards.put(v2ex.source,v2ex);
    Map<String,Boolean> enabled=new HashMap<>();enabled.put(hn.source,true);enabled.put(v2ex.source,true);
    Set<String> aiURLs=NewsEngine.aiHotURLs(boards,enabled);
    check(NewsEngine.hotItems("v2ex",v2ex,false,aiURLs).isEmpty()&&NewsEngine.hotItems("v2ex",v2ex,true,aiURLs).size()==1,"Same URL across boards cannot leak into both views");
    enabled.put(hn.source,false);
    check(NewsEngine.aiHotURLs(boards,enabled).isEmpty(),"Disabled sources do not influence visible routing");
    check(NewsEngine.officialAI(official)&&!NewsEngine.officialAI(media),"Media feed is not an official daily source");
    check(NewsEngine.shouldAutoCheck(official,null,now),"First official check is due");
    NewsEngine.Snapshot snapshot=new NewsEngine.Snapshot(official.id);snapshot.fetched=now-86400000L+1;snapshot.checked=now;
    check(!NewsEngine.shouldAutoCheck(official,snapshot,now),"Official check before 24h is skipped");
    snapshot.fetched--;
    check(NewsEngine.shouldAutoCheck(official,snapshot,now),"Official check exactly at 24h is due");
    snapshot.error="offline";snapshot.checked=now-60000L+1;
    check(!NewsEngine.shouldAutoCheck(official,snapshot,now),"Recent failed check observes cooldown");
    snapshot.checked--;
    check(NewsEngine.shouldAutoCheck(official,snapshot,now),"Failure never advances successful check time");
    snapshot.error="";snapshot.fetched=System.currentTimeMillis();snapshot.checked=snapshot.fetched-1000;
    NewsEngine.Snapshot cached=engine.fetch(official,snapshot,false);
    check(cached.cached&&cached.checked==snapshot.checked&&cached.fetched==snapshot.fetched,"Cached reads must not invent a new check time");
    snapshot.fetched=now;snapshot.checked=now-1000;
    check(!NewsEngine.shouldAutoCheck(official,NewsEngine.Snapshot.from(official.id,snapshot.json()),now+86399999),"Daily success time survives process restart");
    Map<String,NewsEngine.Snapshot> snapshots=new HashMap<>();snapshots.put(official.id,snapshot);
    check(engine.toolSnapshot(new JSONObject(),snapshots)==null,"Tool without source has no automatic check label");
    check(engine.toolSnapshot(new JSONObject().put("sourceId","not-registered"),snapshots)==null,"Unknown tool source cannot claim an update");
    snapshots.put(media.id,new NewsEngine.Snapshot(media.id));
    check(engine.toolSnapshot(new JSONObject().put("sourceId",media.id),snapshots)==null,"Media source cannot claim official tool update");
    JSONObject tool=new JSONObject().put("sourceId",official.id);
    check(engine.toolSnapshot(tool,snapshots)==snapshot,"Tool resolves only its registered official source");
    NewsEngine.Article first=new NewsEngine.Article(),newer=new NewsEngine.Article();first.published=now-2000;newer.published=now-1000;snapshot.items.add(first);snapshot.items.add(newer);
    check(NewsEngine.latestItem(snapshot)==newer,"Tool card chooses latest dated record");
    check(NewsEngine.latestItem(null)==null,"No snapshot means no latest-title claim");
    System.out.println("PASS: AI/technology hot partition, no duplicates, 24h daily checks, cache timestamps and honest tool updates");
  }
  private static void hotViewsAndLegacyState(NewsEngine engine,long now)throws Exception{
    NewsEngine.Source source=engine.sourceMap.get("v2ex");
    String body="[{\"title\":\"Older popular topic\",\"url\":\"https://example.com/topic/a\",\"replies\":\"12\",\"created\":1793188800},{\"title\":\"Newer topic\",\"url\":\"https://example.com/topic/b\",\"replies\":\"3\",\"created\":1793275200}]";
    List<NewsEngine.Article> fresh=NewsEngine.parse(source,body,now);
    NewsEngine.Article first=fresh.get(0),second=fresh.get(1);
    JSONObject oldArticle=first.json();oldArticle.remove("heat");
    NewsEngine.Article old=NewsEngine.Article.from(oldArticle);
    check(old!=null&&old.heat.isEmpty()&&NewsEngine.heatLabel(old).equals("未提供"),"Legacy missing heat is unavailable, not zero");
    check(old.id.equals(first.id),"Restoring old article must not generate a new ID");
    NewsEngine.Snapshot snapshot=new NewsEngine.Snapshot(source.id);
    snapshot.items.add(second);snapshot.items.add(old);snapshot.fetched=snapshot.checked=now;
    List<NewsEngine.Article> ranked=NewsEngine.hotItems(source.id,snapshot);
    check(ranked.size()==2&&ranked.get(0)==old&&ranked.get(1)==second,"Hot list uses rank, not publication time or heat");
    check(snapshot.items.get(0)==second&&old.rank==1&&second.rank==2,"Rendering must not reorder or rewrite snapshot records");
    check(NewsEngine.heatLabel(first).equals("12 回复"),"Heat label must preserve source units");
    check(NewsEngine.heatLabel(second).equals("3 回复"),"Heat strings are not combined");
    NewsEngine.Article label=new NewsEngine.Article();
    check(NewsEngine.heatLabel(label).equals("未提供"),"Empty heat is unavailable");
    for(String heat:new String[]{"0","37 points","9999"," 12 回复 "}){label.heat=heat;check(NewsEngine.heatLabel(label).equals(heat),"Provided heat is displayed verbatim");}
    check(NewsEngine.HOT_SOURCES.equals(Arrays.asList("toutiao","baidu","weibo","zhihu","douyin","bilibili","bilibili-video","tieba","v2ex","hackernews")),"Hot sources list");
    check(NewsEngine.hotItems("v2ex-latest",snapshot).isEmpty()&&NewsEngine.hotItems("ai-codex",snapshot).isEmpty(),"Latest-only and AI feeds excluded from hot view");
    check(NewsEngine.hotItems("v2ex",null).isEmpty(),"Missing hot snapshot is safe");

    JSONObject legacy=new JSONObject().put("schema",3)
      .put("feeds",new JSONObject().put(source.id,snapshot.json()))
      .put("enabled",new JSONObject().put("toutiao",false).put("v2ex",true))
      .put("favorites",new JSONArray().put(oldArticle))
      .put("read",new JSONArray().put(old.id)).put("compact",true).put("large",true).put("unread",true);
    // The old feed and favorite both omit heat; no user data or device files are used.
    legacy.getJSONObject("feeds").getJSONObject(source.id).getJSONArray("items").getJSONObject(1).remove("heat");
    NewsState state=new NewsState();state.restore(engine,legacy);
    check(!state.hotMode,"Old state defaults to latest view");
    check(state.compact&&state.large&&state.unread,"Legacy reading preferences restored");
    check(!state.sourceEnabled.get("toutiao")&&state.sourceEnabled.get("v2ex"),"User source switches restored");
    check(state.sourceEnabled.get("hackernews")==engine.sourceMap.get("hackernews").defaultEnabled,"Missing source switches retain existing defaults");
    check(state.favorites.size()==1&&state.favorites.get(0).id.equals(old.id)&&state.read.contains(old.id),"Legacy favorites and read IDs restored");
    check(state.snapshots.get(source.id).items.get(1).heat.isEmpty(),"Legacy feed heat remains unavailable");
    for(boolean hot:new boolean[]{true,false,true}){
      state.hotMode=hot;
      NewsEngine.hotItems(source.id,state.snapshots.get(source.id));
      JSONObject saved=state.json();NewsState reloaded=new NewsState();reloaded.restore(engine,new JSONObject(saved.toString()));
      check(reloaded.hotMode==hot,"Mode preference survives save/reload");
      check(reloaded.favorites.get(0).id.equals(first.id)&&reloaded.read.contains(first.id),"Mode change and reload preserve favorite/read identity");
      check(!reloaded.sourceEnabled.get("toutiao")&&reloaded.sourceEnabled.get("v2ex"),"Mode changes preserve source switches");
      check(NewsEngine.parse(source,body,now+600000).get(0).id.equals(first.id),"Refreshing same source and URL keeps favorite/read ID");
      state=reloaded;
    }
    // Invalid parsed data must not mutate the successful snapshot.
    String before=snapshot.json().toString();rejects(source,"{broken",now);
    check(snapshot.json().toString().equals(before),"Failed parsing leaves existing items intact");
    // Invalid transport schemes fail before any network access, exercising the real fetch failure path.
    NewsEngine.Source unavailable=new NewsEngine.Source(new JSONObject().put("id",source.id).put("kind","json").put("endpoints",new JSONArray().put("http://example.com/blocked")));
    snapshot.fetched=snapshot.checked=1;
    NewsEngine.Snapshot failed=engine.fetch(unavailable,snapshot,true);
    check(!failed.error.isEmpty()&&failed.items.size()==2&&failed.items.get(1).id.equals(first.id),"Failed refresh preserves previous items and identity");
    check(failed.fetched==1&&failed.checked>1&&snapshot.error.isEmpty(),"Failed refresh changes error/check time, not successful fetch time or original snapshot");
    state.snapshots.put(source.id,failed);NewsState offline=new NewsState();offline.restore(engine,state.json());
    check(offline.snapshots.get(source.id).items.size()==2&&!offline.snapshots.get(source.id).error.isEmpty(),"Failed snapshot survives offline reload");
    check(offline.favorites.get(0).id.equals(first.id)&&offline.read.contains(first.id)&&!offline.sourceEnabled.get("toutiao"),"Offline reload preserves favorites, read state and switches");
    check(NewsEngine.heatLabel(offline.favorites.get(0)).equals("未提供"),"Missing favorite heat stays unavailable after round trip");
    System.out.println("PASS: separate hot lists, original heat labels, legacy state, mode/reload identity and failed-refresh retention");
  }
  public static void main(String[] args)throws Exception{
    NewsEngine engine=new NewsEngine(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
    NewsEngine.Source rss=engine.sourceMap.get("chinanews"),atom=engine.sourceMap.get("ai-codex");
    long now=Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();
    String xml="<rss><channel><item><title><![CDATA[Hello <b>AI</b>]]></title><link>https://example.com/a</link><description>&lt;p&gt;Safe summary&lt;/p&gt;&lt;script&gt;bad()&lt;/script&gt;</description><pubDate>Thu, 29 Oct 2026 18:00:00 +0800</pubDate></item><item><title>Unsafe link</title><link>javascript:alert(1)</link></item><item><title>Duplicate</title><link>https://example.com/a</link></item></channel></rss>";
    List<NewsEngine.Article> a=NewsEngine.parse(rss,xml,now);check(a.size()==1,"URL filtering/dedup");check(a.get(0).title.equals("Hello AI"),"Title cleanup");check(a.get(0).summary.equals("Safe summary"),"Summary cleanup");check(a.get(0).id.matches("[a-f0-9]{24}"),"Stable ID shape");check(a.get(0).id.equals(NewsEngine.parse(rss,xml,now).get(0).id),"Stable ID");
    String feed="<feed><entry><title>v1.2.0-alpha</title><link rel=\"self\" href=\"https://example.com/self\"/><link rel=\"alternate\" href=\"https://example.com/release\"/><updated>2026-10-28T10:00:00Z</updated><content type=\"html\">&lt;p&gt;Release notes&lt;/p&gt;</content></entry></feed>";
    NewsEngine.Article r=NewsEngine.parse(atom,feed,now).get(0);check(r.summary.equals("Release notes"),"Atom encoded HTML");check(r.url.endsWith("/release"),"Atom alternate link");
    rejects(rss,xml.replace("Thu, 29 Oct 2026","Wed, 29 Oct 2025"),now);rejects(rss,"<html>verify access</html>",now);
    String api="[{\"tag_name\":\"v1\",\"html_url\":\"https://example.com/release\",\"body\":\"Notes\",\"published_at\":\"2026-10-28\",\"prerelease\":true},{\"tag_name\":\"draft\",\"draft\":true}]";
    check(NewsEngine.parse(atom,api,now).size()==1,"Draft release skipped");
    for(String url:new String[]{"javascript:alert(1)","file:///etc/passwd","content://contacts/1","intent://open","http://localhost/","http://127.1/","http://0x7f000001/","http://2130706433/","https://[::1]/","https://192.168.0.1/","https://x.local/","https://metadata.google.internal/","https://user:pass@example.com/","https://example.com:8080/","https://example.com/evil.%61pk"})check(NewsEngine.publicURL(url)==null,"Unsafe URL accepted: "+url);
    check(NewsEngine.publicURL("https://example.com/a?x=1&amp;y=2").equals("https://example.com/a?x=1&y=2"),"Entity URL normalization");
    check(NewsEngine.Article.from(new JSONObject("{\"id\":\"x\",\"title\":{},\"url\":\"https://example.com\"}"))==null,"Object fields rejected");
    NewsEngine.Snapshot saved=new NewsEngine.Snapshot(rss.id);saved.items.addAll(a);saved.fetched=now;saved.checked=now;NewsEngine.Snapshot restored=NewsEngine.Snapshot.from(rss.id,saved.json());check(restored.items.size()==1&&restored.items.get(0).title.equals(a.get(0).title),"Offline snapshot round trip");
    hotViewsAndLegacyState(engine,now);
    presentationAndDailyChecks(engine,now);
    featuredAndArchive(engine);
    readingFiltersAndAI(engine,args[0]);
    deviceCorrections(engine,args[0]);
    themePreferences(engine);
    glassThemes();
    linuxDoTransport(args[0]);
    linuxdoModes(args[0]);
    repairs387(engine,args[0]);
    ui390(engine,args[0]);
    aiRouting391(engine,args[0]);
    System.out.println("PASS: native RSS/Atom/JSON, HTML cleanup, draft/stale rejection, URL safety, stable IDs and offline snapshot persistence");
    if(args.length>1&&args[1].equals("--live")){
      List<NewsEngine.Source> selected=new ArrayList<>();for(NewsEngine.Source s:engine.sources)if(s.defaultEnabled)selected.add(s);
      ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<JSONObject>> tasks=new ArrayList<>();
      for(NewsEngine.Source source:selected)tasks.add(pool.submit(()->{long start=System.currentTimeMillis();NewsEngine.Snapshot value=engine.fetch(source,null,false);JSONObject row=new JSONObject().put("id",source.id).put("items",value.items.size()).put("error",value.error).put("ms",System.currentTimeMillis()-start);System.out.println(source.id+": "+value.items.size()+(value.error.isEmpty()?"":" · "+value.error));return row;}));
      JSONArray checks=new JSONArray();int count=0,total=0;for(Future<JSONObject> task:tasks){JSONObject row=task.get();checks.put(row);if(row.optInt("items")>0)count++;total+=row.optInt("items");}pool.shutdown();JSONObject report=new JSONObject().put("checkedAt",Instant.now().toString()).put("runtime","Java HTTPS direct collector; no proxy/backend").put("sources",selected.size()).put("success",count).put("items",total).put("checks",checks);Files.write(Paths.get(args[2]),report.toString(2).getBytes(StandardCharsets.UTF_8));System.out.println("Native direct collection: "+count+"/"+selected.size()+" sources, "+total+" items");
    }
  }
  private static void linuxDoTransport(String sourceFile)throws Exception{
    String config=new String(Files.readAllBytes(Paths.get(sourceFile)),StandardCharsets.UTF_8);
    String fixture="<rss><channel><item><title>开源 Linux 工具发布</title><link>https://linux.do/t/topic/123456</link><description>&lt;p&gt;公开帖子摘要&lt;/p&gt;&lt;script&gt;ignored()&lt;/script&gt;</description><pubDate>"+java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)+"</pubDate></item></channel></rss>";
    NewsEngine ok=new NewsEngine(config,(source,url)->fixture);NewsEngine.Source source=ok.sourceMap.get("linuxdo");
    check(source!=null&&source.experimental&&!source.defaultEnabled,"Unverified linux.do source remains optional/off");
    NewsEngine.Snapshot good=ok.fetch(source,null,true);
    check(good.error.isEmpty()&&good.items.size()==1&&good.channel.contains("ECH"),"Official RSS uses ordinary Article normalization");
    NewsEngine.Article item=good.items.get(0);check(item.summary.equals("公开帖子摘要")&&item.url.equals("https://linux.do/t/topic/123456"),"Feed HTML becomes plain text and keeps canonical link");
    NewsState state=new NewsState();state.restore(ok,new JSONObject().put("favorites",new JSONArray().put(item.json())).put("read",new JSONArray().put(item.id)));
    check(!state.sourceEnabled.get(source.id),"Older state defaults the new experimental source off");
    good.fetched=good.checked=1;String before=good.json().toString();
    for(String error:new String[]{"linux.do 需要访问验证","加密 DNS 不可用","证书验证失败"}){
      NewsEngine fail=new NewsEngine(config,(s,url)->{throw new java.io.IOException(error);});
      NewsEngine.Snapshot failed=fail.fetch(source,good,true);
      check(failed.error.equals(error)&&failed.fetched==1&&failed.checked>1&&failed.items.get(0).id.equals(item.id),"Transport error retains successful snapshot/IDs: "+error);
      check(good.json().toString().equals(before),"Failure does not mutate original snapshot");state.linuxTabs.put("default",failed);
      NewsState restored=new NewsState();restored.restore(ok,state.json());
      check(restored.favorites.get(0).id.equals(item.id)&&restored.read.contains(item.id)&&restored.linuxTabs.get("default").items.get(0).id.equals(item.id),"Failed source reload retains favorites/read/cache");
    }
    NewsEngine malformed=new NewsEngine(config,(s,url)->"<html>challenge-platform</html>");
    check(malformed.fetch(source,good,true).items.get(0).id.equals(item.id),"Challenge/invalid feed never erases old items");
    int[] requests={0};NewsEngine retry=new NewsEngine(config,(s,url)->{check(s.id.equals("linuxdo"),"One-shot retry must only request linux.do");requests[0]++;return fixture;});
    NewsEngine.Snapshot recent=NewsEngine.Snapshot.from(source.id,good.json());recent.checked=recent.fetched=System.currentTimeMillis();recent.error="加密 DNS 不可用";
    check(retry.fetch(source,recent,true).cached&&requests[0]==0,"Ordinary update retains recent-error cooldown");
    NewsEngine.Snapshot retried=retry.recheck(source,recent);
    check(requests[0]==1&&retried.error.isEmpty()&&!retried.cached&&retried.items.get(0).id.equals(item.id),"Explicit one-shot retry performs exactly one fresh request and keeps IDs");
    int[] failures={0};NewsEngine offline=new NewsEngine(config,(s,url)->{failures[0]++;throw new java.io.IOException("加密 DNS 不可用");});
    NewsEngine.Snapshot stillFailed=offline.recheck(source,recent);
    check(failures[0]==1&&!stillFailed.error.isEmpty()&&stillFailed.fetched==recent.fetched&&stillFailed.items.get(0).id.equals(item.id),"One failed manual request keeps previous content without automatic retry");
    try{new LinuxDoTransport("").get(source,"https://linux.do/login");throw new AssertionError("Unexpected native endpoint accepted");}
    catch(java.io.IOException expected){check(expected.getMessage().contains("非登记"),"Endpoint must be checked before loading native library");}
    NewsEngine.Source other=new NewsEngine.Source(new JSONObject().put("id","other").put("endpoints",new JSONArray().put("http://example.com/a")));
    try{new LinuxDoTransport("").get(other,"http://example.com/a");throw new AssertionError("Unexpected insecure transport");}
    catch(java.io.IOException expected){check(expected.getMessage().contains("非登记"),"Other sources keep original HTTPS validation, no native library dependency");}
    System.out.println("PASS: optional linux.do source, bounded RSS normalization, transport/parse failure retention, old-state identity and fixed native endpoints");
  }
  private static String topicJson(long id,long op,long views,long created,String title){
    return "{\"id\":"+id+",\"slug\":\"topic\",\"fancy_title\":\""+title+"\",\"views\":"+views
      +",\"created_at\":\""+java.time.Instant.ofEpochMilli(created)+"\",\"excerpt\":\"摘要"+id+"\""
      +",\"posters\":[{\"description\":\"原始发帖人\",\"user_id\":"+op+"}]}";
  }
  private static void linuxdoModes(String sourceFile)throws Exception{
    String config=new String(Files.readAllBytes(Paths.get(sourceFile)),StandardCharsets.UTF_8);
    long now=System.currentTimeMillis();
    long recent=now-10L*86400000, old=now-120L*86400000;
    String users="[{\"id\":1,\"username\":\"neo\"},{\"id\":2,\"username\":\"alice\"},{\"id\":3,\"username\":\"bob\"}]";
    // Views candidates: t1 by Neo (dropped), t2 recent alice, t3 old bob (dropped), t4 recent alice.
    String viewsBody="{\"users\":"+users+",\"topic_list\":{\"topics\":["
      +topicJson(1,1,99999,recent,"Neo帖")+","
      +topicJson(2,2,9000,recent,"Alice高")+","
      +topicJson(3,3,8000,old,"Bob旧")+","
      +topicJson(4,2,5000,recent,"Alice低")+"]}}";
    // Latest candidates delivered out of order; client must sort by created time desc.
    String latestBody="{\"users\":"+users+",\"topic_list\":{\"topics\":["
      +topicJson(5,2,100,recent-5000,"较早")+","
      +topicJson(6,3,200,recent+5000,"较新")+"]}}";
    String nowRss=java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
      .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
    String rssBody="<rss><channel><item><title>默认RSS帖</title>"
      +"<link>https://linux.do/t/topic/7</link><pubDate>"+nowRss+"</pubDate></item></channel></rss>";
    String topicDetail="{\"id\":2,\"post_stream\":{\"posts\":[{\"post_number\":1,\"post_type\":1,"
      +"\"cooked\":\"<p>第一段内容</p><p>第二段<br>换行</p><ul><li>项目一</li><li>项目二</li></ul>\"}]}}";
    NewsEngine eng=new NewsEngine(config,(s,url)->{
      if(url.equals(NewsEngine.LINUXDO_VIEWS_ENDPOINT))return viewsBody;
      if(url.equals(NewsEngine.LINUXDO_LATEST_ENDPOINT))return latestBody;
      if(url.startsWith("https://linux.do/t/"))return topicDetail;
      return rssBody;
    });
    NewsEngine.Source source=eng.sourceMap.get("linuxdo");

    // Views: Neo and >90d dropped, remaining ordered by views desc.
    NewsEngine.Snapshot views=eng.fetchLinuxDo(source,null,NewsEngine.LINUXDO_MODE_VIEWS);
    check(views.error.isEmpty()&&views.items.size()==2,"Views mode keeps two eligible topics");
    check(views.items.get(0).title.equals("Alice高")&&views.items.get(1).title.equals("Alice低"),"Eligible topics sorted by views");
    check(views.items.get(0).heat.equals("9000 浏览")&&views.items.get(0).url.equals("https://linux.do/t/topic/2"),"Views heat and canonical url");
    check(views.channel.contains("过滤"),"Views channel notes filtering");

    // Latest: re-sorted by creation time, newest first.
    NewsEngine.Snapshot latest=eng.fetchLinuxDo(source,null,NewsEngine.LINUXDO_MODE_LATEST);
    check(latest.items.size()==2&&latest.items.get(0).title.equals("较新"),"Latest mode re-sorts by created time desc");

    // Default: ordinary RSS normalization.
    NewsEngine.Snapshot def=eng.fetchLinuxDo(source,null,NewsEngine.LINUXDO_MODE_DEFAULT);
    check(def.items.size()==1&&def.items.get(0).title.equals("默认RSS帖"),"Default mode uses RSS parse");

    // Full topic text: first post cooked HTML cleaned to plain text with line breaks.
    String full=eng.fetchLinuxTopic(source,2);
    check(full.contains("第一段内容")&&full.contains("项目一")&&full.contains("换行")&&!full.contains("<p>"),"Full text cleaned to plain text with line breaks and list markers");

    // 50-item cap still applies to the views mode.
    StringBuilder many=new StringBuilder("{\"users\":"+users+",\"topic_list\":{\"topics\":[");
    for(int i=1;i<=60;i++){if(i>1)many.append(',');many.append(topicJson(100+i,2,1000-i,recent,"帖"+i));}
    many.append("]}}");
    NewsEngine big=new NewsEngine(config,(s,url)->many.toString());
    check(big.fetchLinuxDo(source,null,NewsEngine.LINUXDO_MODE_VIEWS).items.size()==50,"Views mode caps at 50");

    // Three sub-page snapshots persist in linuxTabs and round-trip; legacy feed migrates to default.
    NewsState state=new NewsState();
    state.linuxTabs.put("default",def);state.linuxTabs.put("views",views);state.linuxTabs.put("latest",latest);
    JSONObject saved=state.json();
    NewsState reloaded=new NewsState();reloaded.restore(eng,new JSONObject(saved.toString()));
    check(reloaded.linuxTabs.size()==3&&reloaded.linuxTabs.get("views").items.size()==2,"linuxTabs round-trip");
    JSONObject legacy=new JSONObject().put("feeds",new JSONObject().put("linuxdo",def.json()));
    NewsState migrated=new NewsState();migrated.restore(eng,legacy);
    check(migrated.linuxTabs.get("default")!=null&&migrated.snapshots.get("linuxdo")==null,"Legacy linuxdo snapshot migrates to default tab");

    // Endpoint guard still rejects unregistered paths before native load.
    try{new LinuxDoTransport("").get(source,"https://linux.do/login");throw new AssertionError("Unexpected endpoint accepted");}
    catch(java.io.IOException expected){
      check(expected.getMessage().contains("非登记"),"Unregistered endpoint rejected");
    }
    System.out.println("PASS: linux.do three sub-pages, Neo/90-day filtering, views/time sorting, full text, 50 cap and linuxTabs persistence");
  }

  private static void repairs387(NewsEngine engine,String sourceFile)throws Exception{
    JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(sourceFile).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    check(!engine.sourceMap.containsKey("toms-hardware"),"Removed feed cannot be fetched or offered in settings");
    Set<String> tools=ReadingRules.toolSources(catalog);
    for(NewsEngine.Source s:engine.sources){
      if(s.defaultEnabled)check(FeaturedNews.latestSource(s)||FeaturedNews.stationSource(s.id)
        ||NewsEngine.HOT_SOURCES.contains(s.id)||ReadingRules.platformSource(catalog,s)||tools.contains(s.id),"Enabled source has a list route: "+s.id);
      if(s.category.equals("ai"))check(Arrays.asList("official","media","aggregator").contains(s.authority),"AI authority is explicit: "+s.id);
    }
    for(String id:new String[]{"sspai","gcores","lobsters","slashdot"})check(FeaturedNews.latestSource(engine.sourceMap.get(id)),"Community RSS reaches latest: "+id);
    for(String id:new String[]{"linuxdo","v2ex","v2ex-latest","hackernews"})check(!FeaturedNews.latestSource(engine.sourceMap.get(id)),"Existing source separation preserved: "+id);
    NewsEngine.Source v=engine.sourceMap.get("v2ex-latest");
    NewsEngine.Article tech=report(v.id,"Rust SDK 开源发布",1,"v-tech"),chat=report(v.id,"周末去哪儿吃饭",1,"v-chat");
    check(ReadingRules.platformSource(catalog,v)&&ReadingRules.platformArticle(v,tech)&&!ReadingRules.platformArticle(v,chat),"V2EX latest reaches AI with topic filtering");
    check(ReadingRules.archivable(v,tech)&&!ReadingRules.archivable(v,chat),"V2EX latest archive keeps the same filter");
    check(FeaturedNews.stationSource("bilibili-video"),"Popular videos have a station list");
    NewsEngine.Source videos=engine.sourceMap.get("bilibili-video");
    String videoBody="{\"data\":{\"list\":[{\"title\":\"AI one\",\"bvid\":\"BV1test\",\"stat\":{\"view\":1234}},{\"title\":\"AI two\",\"bvid\":\"BV2test\"}]}}";
    List<NewsEngine.Article> videoItems=engine.parse(videos,videoBody,System.currentTimeMillis());
    check(videoItems.get(0).heat.equals("1234 播放")&&videoItems.get(1).heat.isEmpty(),"Video metrics preserve supplied values without inventing missing counts");
    List<NewsEngine.Article> douyinItems=engine.parse(engine.sourceMap.get("douyin"),"{\"word_list\":[{\"word\":\"AI agents\",\"hot_value\":12345}]}",System.currentTimeMillis());
    check(douyinItems.get(0).url.equals("https://www.douyin.com/search/AI%20agents"),"Search path encodes spaces as path segments");
    NewsEngine.Snapshot video=new NewsEngine.Snapshot("bilibili-video");video.fetched=1;video.items.add(report(video.source,"AI 芯片开发",1,"ai-video"));
    check(ReadingRules.aiBoard(video.source,video),"Technology videos are visible in the AI partition too");
    Map<String,NewsEngine.Snapshot> snapshots=new HashMap<>();
    for(String id:new String[]{"ai-media-ithome","ai-media-ifanr","ai-aihot","qbitai"}){
      check(!NewsEngine.officialAI(engine.sourceMap.get(id)),"Media/aggregators do not impersonate vendors: "+id);
      NewsEngine.Snapshot media=new NewsEngine.Snapshot(id);media.fetched=99999;media.items.add(report(id,"发布 GPT-99.0",System.currentTimeMillis(),"media-release"));snapshots.put(id,media);
    }
    NewsEngine.Snapshot official=new NewsEngine.Snapshot("ai-openai");official.fetched=100;snapshots.put(official.source,official);
    check(ReadingRules.latestModelCheck(catalog,engine,snapshots)==100&&ReadingRules.newModelNotices(catalog,engine,snapshots).isEmpty(),"Only official evidence affects model checks and release notices");

    long now=System.currentTimeMillis(),recent=now-86400000L;
    JSONArray data=new JSONArray();
    for(int i=1;i<=60;i++)data.put(new JSONObject(topicJson(1000+i,2,i,recent,"候选"+i)));
    data.put(new JSONObject(topicJson(9999,2,9000000000L,recent,"高浏览量尾项")));
    data.put(new JSONObject(topicJson(8888,1,Long.MAX_VALUE,recent,"Neo omitted from users")));
    data.put(new JSONObject(topicJson(7777,2,Long.MAX_VALUE,now-120L*86400000,"Old")));
    String body=new JSONObject().put("users",new JSONArray().put(new JSONObject().put("id",2).put("username","alice")))
      .put("topic_list",new JSONObject().put("topics",data)).toString();
    String cooked="<script>bad()</script><p>&lt;stdio.h&gt; &amp; &lt;em&gt;literal&lt;/em&gt;</p><pre><code>if (a &lt; b &amp;&amp; b &gt; 0)\nnext</code></pre><p><a title=\"x > y\">link</a></p><ul><li>item</li></ul>";
    String detail=new JSONObject().put("post_stream",new JSONObject().put("posts",new JSONArray().put(new JSONObject().put("post_number",1).put("post_type",1).put("cooked",cooked)))).toString();
    String config=new String(Files.readAllBytes(Paths.get(sourceFile)),StandardCharsets.UTF_8);
    NewsEngine fixture=new NewsEngine(config,(s,url)->url.startsWith("https://linux.do/t/")?detail:body);
    NewsEngine.Source linux=fixture.sourceMap.get("linuxdo");NewsEngine.Snapshot ranked=fixture.fetchLinuxDo(linux,null,NewsEngine.LINUXDO_MODE_VIEWS);
    check(ranked.error.isEmpty()&&ranked.items.size()==50&&ranked.items.get(0).url.endsWith("/9999"),"Sort all eligible candidates before taking 50, using long metrics");
    check(ranked.items.get(49).heat.equals("12 浏览")&&ranked.items.get(0).rank==1,"Highest candidates and ranks survive cap");
    for(NewsEngine.Article a:ranked.items)check(!a.url.endsWith("/8888")&&!a.url.endsWith("/7777"),"Neo ID and old records excluded before ranking");
    // Latest's cap can omit ties; compare a single shared topic deterministically.
    NewsEngine single=new NewsEngine(config,(s,url)->new JSONObject().put("topic_list",new JSONObject().put("topics",new JSONArray().put(data.getJSONObject(60)))).toString());
    check(single.fetchLinuxDo(linux,null,NewsEngine.LINUXDO_MODE_LATEST).items.get(0).id.equals(ranked.items.get(0).id),"Ranking never changes topic identity");
    String text=fixture.fetchLinuxTopic(linux,9999);
    check(text.contains("<stdio.h>")&&text.contains("<em>literal</em>")&&text.contains("a < b && b > 0")&&text.contains("\nnext"),"Encoded code and paragraph text survive HTML cleanup");
    check(!text.contains("bad()")&&!text.contains("title=")&&text.contains("link"),"Real markup/script and quoted attributes are removed");
    NewsState state=new NewsState();state.linuxTabs.put("default",ranked);NewsEngine.Snapshot failed=new NewsEngine.Snapshot("linuxdo");failed.checked=Long.MAX_VALUE;failed.error="offline";state.linuxTabs.put("views",failed);
    check(state.latestLinuxSnapshot()==failed,"Settings uses the most recently checked linux sub-page");
    NewsEngine.Article favorite=report("toms-hardware","旧收藏",1,"old-removed-source");state.favorites.add(favorite);state.read.add(favorite.id);state.sourceEnabled.put(v.id,false);
    NewsState restored=new NewsState();restored.restore(engine,state.json());
    check(restored.favorites.get(0).id.equals(favorite.id)&&restored.read.contains(favorite.id)&&!restored.sourceEnabled.get(v.id),"Feed removal preserves old favorites/read and explicit switches");
    ArticleTextCache cache=new ArticleTextCache(2,10);cache.put("a","aaa");cache.put("b","bbb");cache.get("a");cache.put("c","ccc");
    check(cache.get("b")==null&&cache.get("a").equals("aaa"),"Text cache evicts least recently used entry");cache.put("big","01234567890");check(cache.get("big")==null,"Oversized text is displayed without being retained");
    ExecutorService workers=Executors.newFixedThreadPool(4);List<Future<?>> jobs=new ArrayList<>();
    try{for(int n=0;n<4;n++){final int thread=n;jobs.add(workers.submit(()->{for(int i=0;i<100;i++){cache.put(thread+"-"+i,"value");cache.get("a");}}));}for(Future<?> job:jobs)job.get();}finally{workers.shutdownNow();}
    check(cache.size()<=2&&cache.chars()<=10,"Concurrent cache access respects both bounds");
    System.out.println("PASS: source routes, explicit publisher authority, adversarial ranking/text, removed-source state and bounded concurrent text cache");
  }
  private static void ui390(NewsEngine engine,String sourceFile)throws Exception{
    JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(sourceFile).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    for(NewsEngine.Source source:engine.sources)if(FeaturedNews.latestSource(source)||ReadingRules.platformSource(catalog,source))
      check(Arrays.asList("china","global").contains(SourceFilters.region(catalog,source)),"Every visible source has an explicit region group: "+source.id);
    check(SourceFilters.region(catalog,engine.sourceMap.get("chinanews-world")).equals("china"),"International reporting does not change publisher region");
    check(SourceFilters.region(catalog,engine.sourceMap.get("ai-qwen-code")).equals("china"),"Chinese publisher is not classified by GitHub hosting");
    check(SourceFilters.region(catalog,engine.sourceMap.get("ai-openai")).equals("global"),"Official global provider group");
    check(SourceFilters.tags(engine.sourceMap.get("nature")).contains("science"),"Science sources have their own tag");
    check(SourceFilters.tags(engine.sourceMap.get("cnblogs")).contains("development"),"Development sources have their own tag");
    Set<String> tags=new HashSet<>(Arrays.asList("science","business"));
    check(SourceFilters.news(catalog,engine.sourceMap.get("nature"),"global",tags),"News tag multiselect takes union within region");
    check(!SourceFilters.news(catalog,engine.sourceMap.get("nature"),"china",tags),"Region and tags intersect");
    check(SourceFilters.news(catalog,engine.sourceMap.get("tmtpost"),"china",tags),"Domestic finance group");
    check(!SourceFilters.news(catalog,engine.sourceMap.get("ithome"),"china",tags),"Unselected tags do not leak");
    Set<String> sources=new HashSet<>(Arrays.asList("ai-media-ithome","ai-aihot"));
    check(SourceFilters.platform(catalog,engine.sourceMap.get("ai-aihot"),"china",sources)&&!SourceFilters.platform(catalog,engine.sourceMap.get("ai-openai"),"all",sources),"Platform filters exact feed IDs, not mentioned manufacturers");
    check(!SourceFilters.platform(catalog,engine.sourceMap.get("ai-aihot"),"global",sources),"Source multiselect respects the first-level region");
    Map<String,NewsEngine.Snapshot> snapshots=new HashMap<>();
    NewsEngine.Snapshot low=new NewsEngine.Snapshot("ithome"),high=new NewsEngine.Snapshot("ifanr"),failed=new NewsEngine.Snapshot("nature");
    low.items.add(report(low.source,"One",1,"one"));high.items.add(report(high.source,"Two",1,"two"));high.items.add(report(high.source,"Three",1,"three"));failed.error="offline";
    snapshots.put(low.source,low);snapshots.put(high.source,high);snapshots.put(failed.source,failed);
    List<NewsEngine.Source> input=Arrays.asList(engine.sourceMap.get("ithome"),engine.sourceMap.get("npr"),engine.sourceMap.get("nature"),engine.sourceMap.get("linuxdo"),engine.sourceMap.get("ifanr"),engine.sourceMap.get("cnet"));
    List<NewsEngine.Source> ordered=SourceFilters.settingsOrder(input,snapshots,null);
    String[] expected={"linuxdo","nature","ifanr","ithome","npr","cnet"};
    for(int i=0;i<expected.length;i++)check(ordered.get(i).id.equals(expected[i]),"Settings priority/count/stable-tie order at "+i);
    check(input.get(0).id.equals("ithome"),"Settings sorting does not mutate the source registry");
    System.out.println("PASS: source-region groups, two-level news tags, exact feed selection and stable settings priorities");
  }

  private static void aiRouting391(NewsEngine engine,String sourceFile)throws Exception{
    JSONObject catalog=new JSONObject(new String(Files.readAllBytes(Paths.get(sourceFile).getParent().getParent().resolve("internal/catalog/ai_catalog.json")),StandardCharsets.UTF_8));
    long now=System.currentTimeMillis();
    NewsEngine.Source infoq=engine.sourceMap.get("infoq-cn"),blogs=engine.sourceMap.get("cnblogs"),techcrunch=engine.sourceMap.get("techcrunch");
    for(String title:new String[]{"AI研发平台正式发布","生成式AI推理引擎","AIGC 应用进展","LLM inference released","GPT5 推理优化","机器学习模型训练","Claude 新版本发布"})check(NewsEngine.isAI(report(infoq.id,title,now,"ai-topic")),"AI-only topic recognized: "+title);
    for(String title:new String[]{"Java Agent 字节码监控","Rust SDK 数据库开发实践","Daily mail delivery service","Airbnb 旅游商业分析"})check(!NewsEngine.isAI(report(infoq.id,title,now,"plain-topic")),"General technology/text is not automatically AI: "+title);
    String mediaFeed="<rss><channel><item><title>AI图像模型发布</title><link>https://www.ithome.com/ai391.htm</link></item><item><title>Java数据库架构实践</title><link>https://www.ithome.com/java391.htm</link></item></channel></rss>";
    List<NewsEngine.Article> mediaItems=NewsEngine.parse(engine.sourceMap.get("ai-media-ithome"),mediaFeed,now);
    check(mediaItems.size()==1&&mediaItems.get(0).title.equals("AI图像模型发布"),"Dedicated media RSS uses the same AI-only topic rules as mixed-source routing");
    NewsEngine.Article ai=report(infoq.id,"开源AI推理引擎正式发布支持全新功能",now,"routing-ai-one"),ai2=report(techcrunch.id,ai.title,now-1000,"routing-ai-two");
    NewsEngine.Article general=report(infoq.id,"Java数据库连接池架构优化实践详解",now,"routing-news-one"),general2=report(blogs.id,general.title,now-1000,"routing-news-two");
    String originalId=ai.id,originalCategory=ai.category;
    check(ReadingRules.platformSource(catalog,infoq)&&ReadingRules.platformArticle(infoq,ai)&&!ReadingRules.platformArticle(infoq,general),"InfoQ platform stream takes only AI posts");
    check(!ReadingRules.newsArticle(infoq,ai)&&ReadingRules.newsArticle(infoq,general),"News and AI partition mixed-source posts");
    check(!NewsEngine.officialAI(infoq),"Mixed-source AI stories do not become official vendor evidence");
    NewsEngine.Snapshot snapshot=new NewsEngine.Snapshot(infoq.id);snapshot.items.add(ai);snapshot.items.add(general);snapshot.fetched=snapshot.checked=now;
    check(ReadingRules.platformChoice(catalog,infoq,snapshot),"Source picker includes a cached mixed-source AI story");
    NewsEngine.Snapshot ordinary=new NewsEngine.Snapshot(infoq.id);ordinary.items.add(general);check(!ReadingRules.platformChoice(catalog,infoq,ordinary),"Source picker omits mixed feeds with no cached AI stories");
    Map<String,Boolean> enabled=new HashMap<>();for(NewsEngine.Source source:engine.sources)enabled.put(source.id,true);
    List<NewsEngine.Article> reports=Arrays.asList(ai,ai2,general,general2);
    List<FeaturedNews.Event> news=FeaturedNews.group(reports,engine.sourceMap,enabled,false,Collections.emptySet()),intel=FeaturedNews.group(reports,engine.sourceMap,enabled,true,Collections.emptySet());
    check(news.size()==1&&news.get(0).reports.contains(general)&&!news.get(0).reports.contains(ai),"News featured excludes relocated AI reports");
    check(intel.size()==1&&intel.get(0).reports.contains(ai)&&!intel.get(0).reports.contains(general),"AI featured includes relocated reports without general engineering");
    NewsState state=new NewsState();state.snapshots.put(infoq.id,snapshot);state.favorites.add(ai);state.read.add(ai.id);state.sourceEnabled.put(infoq.id,false);
    NewsState restored=new NewsState();restored.restore(engine,state.json());NewsEngine.Article saved=restored.snapshots.get(infoq.id).items.get(0);
    check(saved.id.equals(originalId)&&saved.source.equals(infoq.id)&&saved.category.equals(originalCategory)&&restored.read.contains(originalId)&&restored.favorites.get(0).id.equals(originalId)&&!restored.sourceEnabled.get(infoq.id),"Routing leaves IDs/categories/favorites/read/source switches untouched");
    NewsArchive archive=new NewsArchive();archive.accept(snapshot,engine,now);NewsEngine.Snapshot second=new NewsEngine.Snapshot(techcrunch.id);second.fetched=second.checked=now;second.items.add(ai2);archive.accept(second,engine,now);
    NewsArchive recovered=new NewsArchive();recovered.restore(archive.json(),engine);
    check(FeaturedNews.group(recovered.articles(),engine.sourceMap,enabled,true,Collections.emptySet()).size()==1,"Existing local archive re-routes without backfill or data rewrite");
    check(ReadingRules.latestModelCheck(catalog,engine,Collections.singletonMap(infoq.id,snapshot))==0,"AI media routing does not advance official model-check timestamp");
    System.out.println("PASS: per-article AI routing, news/AI featured partition, cached source choices and unchanged legacy reading/archive identity");
  }

}
