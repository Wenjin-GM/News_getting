package cn.yilan.news;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import cn.yilan.news.NewsEngine.*;

/** Native offline-first reader; every network request goes directly to a registered news source. */
public final class MainActivity extends Activity {
  private static final int GREEN=0xff315b45,INK=0xff243c31,MUTED=0xff748371,CREAM=0xfff5f6f0;
  private static final String[] CATS={"all","news","tech","finance"};
  private static final String[] CAT_NAMES={"全部","新闻","科技","财经"};
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService network=Executors.newFixedThreadPool(4),disk=Executors.newSingleThreadExecutor();
  private final NewsState savedState=new NewsState();
  private final Map<String,Snapshot> snapshots=savedState.snapshots;
  private final Map<String,Boolean> sourceEnabled=savedState.sourceEnabled;
  private final List<Article> favorites=savedState.favorites;
  private final Set<String> read=savedState.read,compare=new LinkedHashSet<>();
  private NewsEngine engine;private JSONObject catalog;private AtomicFile storage,archiveStorage;
  private LinearLayout body,nav;private ScrollView scroll;private ProgressBar progress;private EditText search;
  private int tab=0,aiMode=0,limit=30,hotLimit=10,done=0,total=0,updated=0,failed=0,cached=0;
  private String query="",category="all",provider="all";private boolean busy=false,destroyed=false,compact=false,large=false,unread=false,hotMode=false;
  private Button refresh;
  private final Set<String> categories=new LinkedHashSet<>(),vendors=new LinkedHashSet<>(),collapsed=new HashSet<>(),historyAttempts=new HashSet<>();
  private final Map<String,String> historyResults=new HashMap<>();
  private String region="all",toolType="all",pendingDay="";private boolean pendingAI,userScrolling;
  private final Map<Boolean,String> timelineSince=new HashMap<>(),missingDays=new HashMap<>();
  private final Set<String> expandedDates=new HashSet<>(),initializedTimelines=new HashSet<>();

  private final NewsArchive archive=new NewsArchive();
  private List<FeaturedNews.Event> featured=new ArrayList<>();
  private String featuredKey="",builtFeaturedKey="";
  private boolean featuredMode=false,archiveDirty=false;
  private final Runnable archiveSaveTask=()->saveArchive();
  private final Runnable saveTask=()->saveNow();
  private final Runnable renderTask=()->render(true);
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private GradientDrawable shape(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
  private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(large?size*1.12f:size);t.setTextColor(color);t.setIncludeFontPadding(true);t.setLineSpacing(dp(3),1f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
  private Button button(String label,boolean selected,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(13);b.setTextColor(selected?Color.WHITE:GREEN);b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setBackground(shape(selected?GREEN:0xffeaf0e3,0,11));b.setOnClickListener(v->action.run());return b;}
  private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
  private void gap(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
  private LinearLayout card(){LinearLayout c=vertical();c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(shape(Color.WHITE,0xffe7ebdf,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));body.addView(c,p);return c;}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  private String asset(String name)throws IOException{try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("内置资料过大");out.write(b,0,n);}return out.toString("UTF-8");}}
  @Override public void onCreate(Bundle state){super.onCreate(state);try{engine=new NewsEngine(asset("sources.json"));catalog=new JSONObject(asset("ai-catalog.json"));storage=new AtomicFile(new File(getFilesDir(),"native-news-v3.json"));for(Source s:engine.sources)sourceEnabled.put(s.id,s.defaultEnabled);load();archiveStorage=new AtomicFile(new File(getFilesDir(),"news-archive-v1.json"));loadArchive();chrome();render(false);handler.post(()->refresh(false));}catch(Exception e){TextView t=text("应用初始化失败："+NewsEngine.error(e),16,INK,false);t.setPadding(dp(24),dp(50),dp(24),0);setContentView(t);}}
  private void chrome(){
    LinearLayout root=vertical();root.setBackgroundColor(CREAM);
    if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});else root.setFitsSystemWindows(true);
    LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(18),dp(8),dp(18),dp(8));
    search=new EditText(this);search.setSingleLine(true);search.setHint("搜索");search.setTextSize(14);search.setPadding(dp(14),0,dp(14),0);search.setBackground(shape(Color.WHITE,0xffe0e6d9,12));
    LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(44),1);sp.setMargins(0,0,dp(8),0);bar.addView(search,sp);
    refresh=button("一键更新",true,()->refresh(true));bar.addView(refresh,new LinearLayout.LayoutParams(dp(100),dp(44)));root.addView(bar);
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString().trim();limit=30;hotLimit=10;render(false);}public void afterTextChanged(Editable e){}});
    progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
    scroll=new ScrollView(this);scroll.setFillViewport(true);
    scroll.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_MOVE)userScrolling=true;else userScrolling=false;return false;});
    scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(userScrolling&&body!=null&&y>oldY&&y+scroll.getHeight()>=body.getHeight()-dp(24)&&(tab==0&&featuredMode||tab==1&&aiMode==4)){userScrolling=false;final boolean ai=tab==1;handler.post(()->showOlder(ai));}});body=vertical();body.setPadding(dp(18),dp(12),dp(18),dp(20));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    nav=new LinearLayout(this);nav.setPadding(dp(8),dp(7),dp(8),dp(7));nav.setBackgroundColor(0xfffbfcf7);root.addView(nav);setContentView(root);drawNav();
  }
  private void drawNav(){nav.removeAllViews();String[] labels={"新闻","AI 专栏","收藏"+(favorites.isEmpty()?"":" · "+favorites.size()),"设置"};for(int i=0;i<labels.length;i++){final int index=i;Button b=button(labels[i],tab==i,()->{tab=index;limit=30;category="all";provider="all";query="";search.setText("");drawNav();render(false);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),0,dp(3),0);nav.addView(b,p);}}

  private void render(boolean keepScroll){if(body==null||destroyed)return;int y=keepScroll?scroll.getScrollY():0;body.removeAllViews();if(tab==3)settings();else if(tab==1)ai();else news();scroll.post(()->scroll.scrollTo(0,y));}
  private LinearLayout chips(LinearLayout parent){HorizontalScrollView h=new HorizontalScrollView(this);h.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);h.addView(row);parent.addView(h);gap(parent,12);return row;}
  private void chip(LinearLayout row,String label,boolean selected,Runnable click){Button b=button(label,selected,click);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.setMargins(0,0,dp(7),0);row.addView(b,p);}
  private boolean match(String value){return query.isEmpty()||value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));}
  private List<Article> articles(boolean ai){List<Article> all=new ArrayList<>();if(tab==2)all.addAll(favorites);else for(Source s:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(s.id))&&ai=="ai".equals(s.category)&&(ai?ReadingRules.platformSource(catalog,s):FeaturedNews.latestSource(s))){Snapshot snap=snapshots.get(s.id);if(snap!=null)all.addAll(snap.items);}all.sort((a,b)->{int c=Long.compare(b.published,a.published);return c!=0?c:Integer.compare(a.rank,b.rank);});Set<String> seen=new HashSet<>();List<Article> out=new ArrayList<>();for(Article a:all){if(!seen.add(a.url)||!match(a.title+" "+a.summary+" "+a.sourceName)||unread&&read.contains(a.id))continue;if(tab==0&&!ReadingRules.category(categories,a.category))continue;if(ai&&!matchesProvider(a))continue;out.add(a);}return out;}
  private boolean providerMention(Article a,String p){Source s=engine.sourceMap.get(a.source);if(s==null||!"rss-ai".equals(s.kind))return false;String value=(a.title+" "+a.summary).toLowerCase(Locale.ROOT);Map<String,String> aliases=new HashMap<>();aliases.put("openai","openai|chatgpt|codex|gpt");aliases.put("anthropic","anthropic|claude");aliases.put("google","google|谷歌|gemini|antigravity");aliases.put("github","github|copilot|微软");aliases.put("alibaba","阿里|qwen|千问|通义|灵码");aliases.put("moonshot","kimi|月之暗面");aliases.put("deepseek","deepseek|深度求索");aliases.put("minimax","minimax|海螺");aliases.put("mistral","mistral|vibe");aliases.put("zai","智谱|zcode|glm");aliases.put("bytedance","字节|trae|豆包");aliases.put("tencent","腾讯|codebuddy|混元|workbuddy");aliases.put("baidu","百度|文心|comate");aliases.put("xai","xai|grok");aliases.put("amazon","amazon|aws|kiro|亚马逊");aliases.put("huggingface","hugging.?face");return java.util.regex.Pattern.compile(aliases.getOrDefault(p,p),java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value).find();}
  private void news(){boolean saved=tab==2;if(!saved){LinearLayout modes=chips(body);chip(modes,"最新",!hotMode&&!featuredMode,()->setNewsMode(0));chip(modes,"分站热榜",hotMode,()->setNewsMode(1));chip(modes,"精选",featuredMode,()->setNewsMode(2));if(featuredMode){featuredNews(false);return;}if(hotMode){hotNews(false);return;}LinearLayout row=new LinearLayout(this);row.addView(smallButton(categories.isEmpty()?"全部分类 ▾":"分类 · "+categories.size()+" ▾",()->pickMany("分类",Arrays.asList(CATS),Arrays.asList(CAT_NAMES),categories,()->{limit=30;render(false);})),new LinearLayout.LayoutParams(0,dp(34),1));row.addView(new Space(this),new LinearLayout.LayoutParams(0,1,1));body.addView(row);gap(body,10);}showArticles(articles(false));}
  private void setNewsMode(int mode){hotMode=mode==1;featuredMode=mode==2;limit=30;hotLimit=10;saveSoon();render(false);}
  private void requestFeatured(boolean ai){
    final Set<String> boards=visibleAIBoards();final String key=ai+":"+boards+":"+archive.revision+":"+sourceEnabled.toString();if(key.equals(featuredKey))return;featuredKey=key;
    final List<Article> input=archive.articles();final Map<String,Boolean> enabled=new LinkedHashMap<>(sourceEnabled);
    disk.execute(()->{List<FeaturedNews.Event> events=FeaturedNews.group(input,engine.sourceMap,enabled,ai,boards);handler.post(()->{if(destroyed||!key.equals(featuredKey))return;featured=events;builtFeaturedKey=key;if(tab==0&&featuredMode||tab==1&&aiMode==4)render(true);});});
  }
  private String timelineFloor(boolean ai){return timelineSince.getOrDefault(ai,FeaturedNews.initialDay(System.currentTimeMillis()));}
  private void showOlder(boolean ai){
    if(destroyed||!(ai?tab==1&&aiMode==4:tab==0&&featuredMode)||!featuredKey.equals(builtFeaturedKey))return;
    String scope=ai?"ai:":"news:",missing=missingDays.get(ai);
    if(missing!=null&&!ReadingRules.hasDay(featured,missing))return;
    String floor=timelineFloor(ai),older=null;
    for(FeaturedNews.Event e:featured)if(e.day().compareTo(floor)<0&&(older==null||e.day().compareTo(older)>0))older=e.day();
    if(older!=null)timelineSince.put(ai,FeaturedNews.earlierDay(older));
    else{String day=ReadingRules.olderGap(featured,System.currentTimeMillis());timelineSince.put(ai,day);missingDays.put(ai,day);expandedDates.add(scope+day);handler.post(()->requestHistory(ai,day));}
    limit+=30;render(true);
  }
  private void featuredNews(boolean ai){
    requestFeatured(ai);body.addView(text("本机已抓来源归并",12,MUTED,false));gap(body,10);
    if(archive.incomplete)body.addView(text("归档容量已满，部分新条目未保存",12,MUTED,false));
    if(!featuredKey.equals(builtFeaturedKey)){body.addView(text("正在归并…",12,MUTED,false));return;}
    String scope=ai?"ai:":"news:";List<FeaturedNews.Event> visible=new ArrayList<>();
    for(FeaturedNews.Event e:featured){boolean matches=false,hasUnread=false;for(Article a:e.reports){matches|=match(a.title+" "+a.summary+" "+a.sourceName);hasUnread|=!read.contains(a.id);}if(matches&&(!unread||hasUnread))visible.add(e);}
    Map<String,List<FeaturedNews.Event>> days=FeaturedNews.days(visible,timelineFloor(ai));
    if(!days.isEmpty()&&initializedTimelines.add(scope))expandedDates.add(scope+days.keySet().iterator().next());
    String missing=missingDays.get(ai);
    if(missing!=null){
      if(ReadingRules.hasDay(featured,missing)){missingDays.remove(ai);missing=null;}
      else days.putIfAbsent(missing,new ArrayList<>());
    }
    int count=0,total=0;
    for(Map.Entry<String,List<FeaturedNews.Event>> day:days.entrySet()){
      String key=scope+day.getKey();boolean expanded=expandedDates.contains(key);
      body.addView(button(day.getKey()+" · "+day.getValue().size()+(expanded?" ▾":" ▸"),false,()->{if(!expandedDates.add(key))expandedDates.remove(key);render(true);}));gap(body,10);
      if(!expanded)continue;
      if(day.getValue().isEmpty())body.addView(text(historyResults.getOrDefault(key,"正在检查该日期…"),12,MUTED,false));
      total+=day.getValue().size();
      for(FeaturedNews.Event e:day.getValue()){
        if(count++>=limit)continue;LinearLayout c=card();TextView title=text(e.title(),17,INK,true);
        boolean allRead=true;for(Article a:e.reports)allRead&=read.contains(a.id);title.setAlpha(allRead?0.65f:1f);title.setOnClickListener(v->openEvent(e));c.addView(title);gap(c,5);c.addView(text(e.publishers.size()+" 家来源 · "+stamp(e.latest,false),11,MUTED,false));
      }
    }
    if(total>limit){body.addView(button("再看 30 条",false,()->{limit+=30;render(true);}));return;}
    if(missing==null)body.addView(button("更早日期",false,()->showOlder(ai)));
  }
  private void openEvent(FeaturedNews.Event event){
    LinearLayout content=vertical();content.setPadding(dp(22),dp(14),dp(22),dp(18));content.addView(text("本机已抓来源归并",12,MUTED,false));
    for(Article a:event.reports){
      read.add(a.id);gap(content,16);TextView title=text(a.title,18,INK,true);title.setOnClickListener(v->open(a));content.addView(title);gap(content,5);content.addView(text(a.sourceName+" · "+publication(a),12,MUTED,false));
      if(!a.summary.isEmpty()){gap(content,8);content.addView(text(a.summary,15,INK,false));}gap(content,8);content.addView(button("原文",false,()->external(a.url)));
    }
    while(read.size()>2000)read.remove(read.iterator().next());saveSoon();render(true);ScrollView view=new ScrollView(this);view.addView(content);new AlertDialog.Builder(this).setView(view).setPositiveButton("关闭",null).show();
  }
  private void hotNews(boolean ai){
    boolean more=false;Set<String> aiURLs=NewsEngine.aiHotURLs(snapshots,sourceEnabled);
    for(String id:NewsEngine.HOT_SOURCES){
      Source source=engine.sourceMap.get(id);if(source==null||!Boolean.TRUE.equals(sourceEnabled.get(id))||(!ai&&!FeaturedNews.stationSource(id))||(ai&&!ReadingRules.aiBoard(id,snapshots.get(id))))continue;
      Snapshot snapshot=snapshots.get(id);List<Article> items=FeaturedNews.hotItems(id,snapshot,ai,aiURLs);
      items.removeIf(a->!match(a.title+" "+a.summary+" "+a.sourceName)||unread&&read.contains(a.id));
      if(ai&&items.isEmpty())continue;
      String boardKey=(ai?"ai:":"news:")+id;body.addView(button(source.name+" · "+items.size()+(collapsed.contains(boardKey)?" ▸":" ▾"),false,()->{if(!collapsed.add(boardKey))collapsed.remove(boardKey);render(true);}));gap(body,10);if(collapsed.contains(boardKey))continue;
      for(int i=0;i<Math.min(hotLimit,items.size());i++)articleCard(items.get(i));
      more|=items.size()>hotLimit;gap(body,18);
    }
    if(more)body.addView(button("每榜再看 10 条",false,()->{hotLimit+=10;render(true);}));
  }
  private void showArticles(List<Article> list){
    for(int i=0;i<Math.min(limit,list.size());i++)articleCard(list.get(i));
    if(list.size()>limit)body.addView(button("再看 30 条",false,()->{limit+=30;render(true);}));
  }
  private void articleCard(Article a){
    LinearLayout c=card();TextView title=text(a.title,17,INK,true);title.setAlpha(read.contains(a.id)?0.65f:1f);
    title.setOnClickListener(v->open(a));c.addView(title);gap(c,5);c.addView(text(a.sourceName+" · "+publication(a),11,MUTED,false));
  }
  private boolean isFavorite(String id){for(Article a:favorites)if(a.id.equals(id))return true;return false;}
  private void open(Article a){
    read.add(a.id);while(read.size()>2000)read.remove(read.iterator().next());saveSoon();render(true);
    LinearLayout content=vertical();content.setPadding(dp(22),dp(14),dp(22),dp(18));
    content.addView(text(a.title,21,INK,true));gap(content,12);
    content.addView(text(a.sourceName+" · "+(a.published>0?stamp(a.published,a.dateOnly):"未提供发布时间"),12,MUTED,false));
    if(NewsEngine.HOT_SOURCES.contains(a.source)){gap(content,8);content.addView(text("热度："+NewsEngine.heatLabel(a),13,GREEN,false));}
    Snapshot snapshot=snapshots.get(a.source);if(snapshot!=null&&!snapshot.error.isEmpty()){gap(content,8);content.addView(text("历史快照",12,MUTED,false));}
    if(!a.summary.isEmpty()){gap(content,16);content.addView(text(a.summary,16,INK,false));}
    gap(content,16);Button star=button(isFavorite(a.id)?"已收藏 ★":"收藏 ☆",isFavorite(a.id),()->{});
    star.setOnClickListener(v->{if(isFavorite(a.id))favorites.removeIf(x->x.id.equals(a.id));else{favorites.add(0,a);if(favorites.size()>500)favorites.remove(500);}star.setText(isFavorite(a.id)?"已收藏 ★":"收藏 ☆");saveSoon();drawNav();render(true);});
    content.addView(star);gap(content,8);content.addView(button("分享",false,()->share(a.title+"\n"+a.url,"分享新闻")));gap(content,8);content.addView(button("原文",false,()->external(a.url)));
    ScrollView reader=new ScrollView(this);reader.addView(content);new AlertDialog.Builder(this).setView(reader).setPositiveButton("关闭",null).show();
  }
  private boolean external(String url){String safe=NewsEngine.publicURL(url);if(safe==null){toast("已阻止非网页、本地网络或可执行文件链接");return false;}try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE));return true;}catch(ActivityNotFoundException e){toast("请安装可用的浏览器以阅读原文");return false;}}
  private void share(String value,String title){Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value);try{startActivity(Intent.createChooser(i,title));}catch(ActivityNotFoundException e){toast("未找到可分享的应用");}}
  private void ai(){
    JSONArray tools=catalog.optJSONArray("tools");LinearLayout modes=chips(body);String[] names={"平台动态","工具","模型","分站热榜","精选"};
    for(int i=0;i<names.length;i++){final int mode=i;chip(modes,names[i],aiMode==i,()->{aiMode=mode;limit=30;hotLimit=10;render(false);});}
    if(aiMode==3){hotNews(true);return;}if(aiMode==4){featuredNews(true);return;}
    platformFilters();if(aiMode==0){showArticles(articles(true));return;}if(aiMode==2){modelPage();return;}
    LinearLayout kinds=chips(body);String[] ids={"all","product","harness","sdk"},labels={"全部类型","产品","Harness","SDK"};
    for(int i=0;i<ids.length;i++){final String id=ids[i];chip(kinds,labels[i],toolType.equals(id),()->{toolType=id;render(false);});}
    for(int i=0;tools!=null&&i<tools.length();i++){JSONObject t=tools.optJSONObject(i);if(t==null||!ReadingRules.provider(catalog,t.optString("provider"),region,vendors)||!match(t.optString("name")+" "+t.optString("description")+" "+t.optString("role")))continue;
      JSONArray k=t.optJSONArray("kinds");boolean product=hasKind(k,"cli")||hasKind(k,"ide")||hasKind(k,"desktop")||hasKind(k,"web");
      if(toolType.equals("product")&&!product||toolType.equals("harness")&&!hasKind(k,"harness")||toolType.equals("sdk")&&!hasKind(k,"sdk")&&!hasKind(k,"framework"))continue;toolCard(t);
    }
  }
  private Button smallButton(String label,Runnable click){Button b=button(label,false,click);b.setTextSize(11);b.setMinHeight(dp(32));b.setMinimumHeight(dp(32));b.setPadding(dp(8),0,dp(8),0);return b;}
  private void pickMany(String title,List<String> ids,List<String> names,Set<String> selected,Runnable done){
    final boolean[] syncing={false};Set<String> next=new LinkedHashSet<>(selected);boolean[] checked=new boolean[ids.size()];
    ReadingRules.syncChoices(ids,next,checked);
    new AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(names.toArray(new String[0]),checked,(dialog,index,on)->{
      if(syncing[0])return;ReadingRules.choose(next,ids.get(index),on);ListView list=((AlertDialog)dialog).getListView();
      syncing[0]=true;try{ReadingRules.syncChoices(ids,next,checked);for(int i=0;i<ids.size();i++)list.setItemChecked(i,checked[i]);}finally{syncing[0]=false;}
    }).setPositiveButton("确定",(d,w)->{selected.clear();selected.addAll(next);done.run();}).setNegativeButton("取消",null).show();
  }
  private void platformFilters(){
    LinearLayout row=new LinearLayout(this);String label=region.equals("china")?"国内平台":region.equals("global")?"国外平台":"全部平台";
    row.addView(smallButton(label+" ▾",()->new AlertDialog.Builder(this).setItems(new String[]{"全部","国外平台","国内平台"},(d,w)->{region=new String[]{"all","global","china"}[w];vendors.clear();render(false);}).show()),new LinearLayout.LayoutParams(0,dp(34),1));
    row.addView(smallButton(vendors.isEmpty()?"全部厂商 ▾":"厂商 · "+vendors.size()+" ▾",()->{
      LinkedHashMap<String,String> choices=new LinkedHashMap<>();choices.put("all","全部");JSONArray ps=catalog.optJSONArray("providers");
      for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&ReadingRules.provider(catalog,p.optString("id"),region,Collections.emptySet()))choices.put(p.optString("id"),p.optString("name"));}
      JSONObject data=catalog.optJSONObject("modelData");ps=data==null?null:data.optJSONArray("profiles");
      for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&ReadingRules.provider(catalog,p.optString("id"),region,Collections.emptySet()))choices.put(p.optString("id"),p.optString("name"));}
      pickMany("厂商",new ArrayList<>(choices.keySet()),new ArrayList<>(choices.values()),vendors,()->render(false));
    }),new LinearLayout.LayoutParams(0,dp(34),1));body.addView(row);gap(body,10);
  }
  private boolean matchesProvider(Article a){
    if(region.equals("all")&&vendors.isEmpty())return true;
    if(ReadingRules.provider(catalog,a.provider,region,vendors))return true;
    JSONArray ps=catalog.optJSONArray("providers");for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&ReadingRules.provider(catalog,p.optString("id"),region,vendors)&&providerMention(a,p.optString("id")))return true;}return false;
  }
  private Set<String> visibleAIBoards(){Set<String> out=new HashSet<>();for(String id:NewsEngine.HOT_SOURCES)if(Boolean.TRUE.equals(sourceEnabled.get(id))&&ReadingRules.aiBoard(id,snapshots.get(id)))out.add(id);return out;}
  private void requestHistory(boolean ai,String day){
    if(day.isEmpty()||destroyed)return;String key=(ai?"ai:":"news:")+day;if(historyAttempts.contains(key))return;
    if(busy){pendingDay=day;pendingAI=ai;return;}historyAttempts.add(key);
    List<Source> selected=new ArrayList<>();for(Source s:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(s.id))&&(ai?("ai".equals(s.category)||"v2ex".equals(s.id)||"hackernews".equals(s.id)):FeaturedNews.latestSource(s)))selected.add(s);
    startRefresh(selected,true,true,key,ai);
  }
  private JSONObject modelData(){JSONObject d=catalog.optJSONObject("modelData");return d==null?new JSONObject():d;}
  private JSONObject modelProfile(String id){JSONArray ps=modelData().optJSONArray("profiles");for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&id.equals(p.optString("id")))return p;}return new JSONObject();}
  private String confidence(JSONObject claim){String c=ReadingRules.confidence(claim);return "verified".equals(c)?"已核实（快照）":"partial".equals(c)?"部分核实（快照）":"未核实";}
  private String claims(JSONObject profile,String key){
    JSONArray list=profile.optJSONArray(key);List<String> lines=new ArrayList<>();for(int i=0;list!=null&&i<list.length();i++){JSONObject c=list.optJSONObject(i);if(c!=null)lines.add(c.optString("text")+" ["+confidence(c)+"]");}return lines.isEmpty()?"未提供":String.join("\n\n",lines);
  }
  private String modelRelease(JSONObject m){Object date=m.opt("releasedAt");return date instanceof String&&!((String)date).isEmpty()?(String)date:"未提供";}
  private void modelPage(){
    long checked=ReadingRules.latestModelCheck(catalog,engine,snapshots);body.addView(text(checked>0?"最近检查成功 "+stamp(checked,false):"最近检查：尚无成功记录",12,MUTED,false));gap(body,10);
    for(Article a:ReadingRules.newModelNotices(catalog,engine,snapshots))if(matchesProvider(a)){LinearLayout c=card();TextView notice=text(a.title+" · 新发布，尚未写入资料",15,INK,true);notice.setOnClickListener(v->open(a));c.addView(notice);c.addView(text(a.sourceName+" · "+publication(a),11,MUTED,false));}
    if(!compare.isEmpty()){body.addView(button("模型对比 · "+compare.size(),true,()->compareModels()));body.addView(smallButton("清空对比",()->{compare.clear();render(true);}));}
    for(JSONObject m:ReadingRules.sortedModels(modelData().optJSONArray("models"))){if(m==null||!ReadingRules.provider(catalog,m.optString("provider"),region,vendors)||!match(m.optString("name")+" "+m.optString("summary")))continue;
      JSONObject profile=modelProfile(m.optString("provider"));LinearLayout c=card();TextView name=text(m.optString("name"),18,INK,true);name.setOnClickListener(v->modelDetails(m));c.addView(name);c.addView(text(profile.optString("name")+" · "+modelRelease(m),11,MUTED,false));gap(c,8);
      TextView features=text("特点："+m.optString("summary"),13,INK,false);features.setMaxLines(3);features.setEllipsize(TextUtils.TruncateAt.END);c.addView(features);String id=m.optString("id");c.addView(button(compare.contains(id)?"已加入对比":"加入对比",compare.contains(id),()->{if(compare.contains(id))compare.remove(id);else if(compare.size()<3)compare.add(id);else toast("最多对比 3 项");render(true);}));
    }
  }
  private void modelDetails(JSONObject m){
    LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(12));JSONObject profile=modelProfile(m.optString("provider"));
    content.addView(text("发布日期 "+modelRelease(m)+"\n特点："+m.optString("summary")+" ["+confidence(m)+"]\n\n"+m.optString("analysis")+"\n\n厂商优势：\n"+claims(profile,"strengths")+"\n\n厂商劣势：\n"+claims(profile,"weaknesses"),14,INK,false));
    Set<String> refs=new LinkedHashSet<>();JSONArray ids=m.optJSONArray("sourceIds");for(int i=0;ids!=null&&i<ids.length();i++)refs.add(ids.optString(i));
    for(String key:new String[]{"strengths","weaknesses"}){JSONArray list=profile.optJSONArray(key);for(int i=0;list!=null&&i<list.length();i++){JSONObject claim=list.optJSONObject(i);ids=claim==null?null:claim.optJSONArray("sourceIds");for(int j=0;ids!=null&&j<ids.length();j++)refs.add(ids.optString(j));}}
    JSONArray sources=modelData().optJSONArray("sources");for(int i=0;sources!=null&&i<sources.length();i++){JSONObject source=sources.optJSONObject(i);if(source!=null&&refs.contains(source.optString("id")))content.addView(smallButton(source.optString("name"),()->external(source.optString("url"))));}
    ScrollView view=new ScrollView(this);view.addView(content);new AlertDialog.Builder(this).setTitle(m.optString("name")).setView(view).setPositiveButton("关闭",null).show();
  }
  private void compareModels(){
    JSONArray models=modelData().optJSONArray("models");TableLayout table=new TableLayout(this);String[] fields={"模型","特点","厂商优势","厂商劣势"};
    for(int row=0;row<fields.length;row++){TableRow tr=new TableRow(this);tr.addView(text(fields[row],12,GREEN,true),new TableRow.LayoutParams(dp(80),-2));
      for(int i=0;models!=null&&i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m==null||!compare.contains(m.optString("id")))continue;JSONObject p=modelProfile(m.optString("provider"));String value=row==0?m.optString("name"):row==1?m.optString("summary")+" ["+confidence(m)+"]":claims(p,row==2?"strengths":"weaknesses");TextView t=text(value,12,INK,row==0);t.setPadding(dp(10),dp(10),dp(10),dp(10));tr.addView(t,new TableRow.LayoutParams(dp(230),-2));}table.addView(tr);
    }
    HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.addView(table);ScrollView view=new ScrollView(this);view.addView(horizontal);new AlertDialog.Builder(this).setTitle("模型对比 · "+modelData().optString("asOf")).setView(view).setPositiveButton("关闭",null).show();
  }
  private static boolean hasKind(JSONArray a,String value){for(int i=0;a!=null&&i<a.length();i++)if(value.equals(a.optString(i)))return true;return false;}
  private String providerName(String id){JSONArray a=catalog.optJSONArray("providers");for(int i=0;a!=null&&i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p!=null&&id.equals(p.optString("id")))return p.optString("name");}return id;}
  private String types(JSONArray a){List<String> names=new ArrayList<>();Map<String,String> map=new HashMap<>();map.put("desktop","桌面");map.put("ide","IDE / 插件");map.put("cli","CLI");map.put("web","Web");map.put("harness","Harness");map.put("sdk","SDK");map.put("framework","通用框架");for(int i=0;a!=null&&i<a.length();i++)names.add(map.getOrDefault(a.optString(i),a.optString(i)));return String.join(" · ",names);}
  private void toolCard(JSONObject t){LinearLayout c=card();c.addView(text(providerName(t.optString("provider")),11,MUTED,false));gap(c,6);c.addView(text(t.optString("name"),18,INK,true));gap(c,5);String open=t.optString("open");c.addView(text(types(t.optJSONArray("kinds"))+"  ·  "+("open".equals(open)?"开放源码":"mixed".equals(open)?"部分组件开源":"未确认核心开源"),11,GREEN,false));gap(c,7);c.addView(text(t.optString("description"),13,MUTED,false));gap(c,7);c.addView(text(t.optString("role"),11,GREEN,false));Snapshot release=engine.toolSnapshot(t,snapshots);Article recentItem=NewsEngine.latestItem(release);if(recentItem!=null){TextView recent=text(recentItem.title,12,MUTED,false);recent.setOnClickListener(v->open(recentItem));c.addView(recent);c.addView(text(recentItem.sourceName+" · "+publication(recentItem),11,MUTED,false));}if(release!=null&&release.checked>0)c.addView(text("检查 "+stamp(release.checked,false),11,MUTED,false));gap(c,10);LinearLayout actions=new LinearLayout(this);actions.addView(button("查看详情",false,()->toolDetails(t)),new LinearLayout.LayoutParams(0,dp(42),1));c.addView(actions);}
  private void toolDetails(JSONObject t){LinearLayout content=vertical();content.setPadding(dp(22),dp(14),dp(22),dp(10));content.addView(text(t.optString("description"),15,INK,false));gap(content,12);content.addView(text("出品方："+providerName(t.optString("provider"))+"\n类型："+types(t.optJSONArray("kinds"))+"\n定位："+t.optString("role")+"\n许可："+t.optString("license")+"\n适用平台："+t.optJSONArray("platforms")+"\n工具资料核对于 "+catalog.optString("checkedAt"),13,MUTED,false));if(!t.optString("note").isEmpty()){gap(content,12);content.addView(text(t.optString("note"),13,GREEN,false));}gap(content,12);JSONArray refs=t.optJSONArray("references");for(int i=0;refs!=null&&i<refs.length();i++){final String url=refs.optString(i);content.addView(button("官方依据 "+(i+1)+" ↗",false,()->external(url)));gap(content,6);}content.addView(button("前往官方产品 / 文档 ↗",true,()->external(t.optString("url"))));ScrollView s=new ScrollView(this);s.addView(content);new AlertDialog.Builder(this).setTitle(t.optString("name")).setView(s).setPositiveButton("关闭",null).show();}
  private void settings(){LinearLayout preferences=card();toggle(preferences,"大字阅读",large,value->{large=value;saveSoon();render(true);});toggle(preferences,"只看未读",unread,value->{unread=value;saveSoon();render(true);});
    LinearLayout share=card();share.addView(button("分享安装包",true,()->shareApk()));
    body.addView(text("信息来源 · "+enabledCount()+" / "+engine.sources.size()+" 已启用",16,GREEN,true));gap(body,10);for(Source source:engine.sources){LinearLayout c=card();Switch sw=new Switch(this);sw.setText(source.name+(source.experimental?" · 实验渠道":""));sw.setTextSize(14);sw.setTextColor(INK);sw.setChecked(Boolean.TRUE.equals(sourceEnabled.get(source.id)));sw.setOnCheckedChangeListener((v,checked)->{sourceEnabled.put(source.id,checked);saveSoon();});c.addView(sw);Snapshot snap=snapshots.get(source.id);String value=snap==null?"尚未获取":(!snap.error.isEmpty()?(snap.items.isEmpty()?"获取失败":"历史快照"):"已获取 "+snap.items.size()+" 条")+" · "+(snap.fetched>0?stamp(snap.fetched,false):"尚无成功快照");c.addView(text(value,11,MUTED,false));if(snap!=null&&!snap.error.isEmpty())c.addView(text(snap.error,11,0xffa56b46,false));c.addView(text(source.home,10,MUTED,false));}}
  private interface Changed{void accept(boolean value);}
  private void toggle(LinearLayout parent,String title,boolean checked,Changed action){Switch s=new Switch(this);s.setText(title);s.setTextSize(13);s.setTextColor(INK);s.setPadding(0,dp(12),0,dp(12));s.setChecked(checked);s.setOnCheckedChangeListener((v,value)->action.accept(value));parent.addView(s);}
  private int enabledCount(){int n=0;for(Boolean enabled:sourceEnabled.values())if(Boolean.TRUE.equals(enabled))n++;return n;}
  private void shareApk(){Uri uri=Uri.parse("content://"+getPackageName()+".apk/release.apk");Intent share=new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newUri(getContentResolver(),"一览独立版 APK",uri));try{startActivity(Intent.createChooser(share,"分享一览安装包"));}catch(ActivityNotFoundException e){toast("没有可接收安装包的分享应用，请使用电脑发送原始 APK");}}
  private void refresh(boolean force){
    if(busy||destroyed||engine==null||body==null)return;
    List<Source> selected=new ArrayList<>();long now=System.currentTimeMillis();
    for(Source s:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(s.id))&&(force||NewsEngine.shouldAutoCheck(s,snapshots.get(s.id),now)))selected.add(s);
    startRefresh(selected,force,false,"",false);
  }
  private void startRefresh(List<Source> selected,boolean force,boolean recheck,String historyKey,boolean ai){
    if(selected.isEmpty()){if(!historyKey.isEmpty()){historyResults.put(historyKey,"没有已启用的相关来源");render(true);}return;}
    busy=true;done=updated=failed=cached=0;total=selected.size();refresh.setEnabled(false);progress.setMax(total);progress.setProgress(0);progress.setVisibility(View.VISIBLE);
    for(Source s:selected){final Snapshot old=snapshots.get(s.id);network.execute(()->{
      Snapshot value=recheck?engine.recheck(s,old):engine.fetch(s,old,force);handler.post(()->{
        if(destroyed)return;snapshots.put(s.id,value);if(!value.cached&&value.error.isEmpty()){long before=archive.revision;archive.accept(value,engine,System.currentTimeMillis());if(archive.revision!=before){archiveDirty=true;handler.removeCallbacks(archiveSaveTask);handler.postDelayed(archiveSaveTask,500);}}done++;if(!value.error.isEmpty())failed++;else if(value.cached)cached++;else updated++;
        progress.setProgress(done);handler.removeCallbacks(renderTask);handler.postDelayed(renderTask,120);saveSoon();
        if(done==total){busy=false;refresh.setEnabled(true);progress.setVisibility(View.GONE);toast("更新 "+updated+" · 沿用 "+cached+" · 失败 "+failed);
          if(!historyKey.isEmpty()){String day=historyKey.substring(historyKey.indexOf(':')+1);boolean returned=false;for(Source src:selected){Snapshot snap=snapshots.get(src.id);if(snap!=null&&snap.error.isEmpty())for(Article a:snap.items)if(a.published>0&&FeaturedNews.day(a.published).equals(day))returned=true;}historyResults.put(historyKey,returned?"来源已返回该日条目，未形成该日独立精选":failed==total?"本次检查失败，旧记录已保留":"来源没有返回这一天（"+day+"）");}
          render(true);if(!pendingDay.isEmpty()){String day=pendingDay;boolean scope=pendingAI;pendingDay="";handler.post(()->requestHistory(scope,day));}}
      });
    });}
  }
  private String publication(Article a){return a.published>0?stamp(a.published,a.dateOnly):"未提供";}
  private void loadArchive(){try{if(archiveStorage.getBaseFile().exists()&&archiveStorage.getBaseFile().length()<=NewsArchive.MAX_BYTES)archive.restore(new JSONObject(new String(archiveStorage.readFully(),StandardCharsets.UTF_8)),engine);}catch(Exception ignored){}}
  private void saveArchive(){
    if(!archiveDirty||archiveStorage==null||disk.isShutdown())return;
    try{final byte[] bytes=archive.json().toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>NewsArchive.MAX_BYTES)throw new IOException("归档过大");archiveDirty=false;
      disk.execute(()->{FileOutputStream out=null;try{out=archiveStorage.startWrite();out.write(bytes);archiveStorage.finishWrite(out);}catch(Exception e){if(out!=null)archiveStorage.failWrite(out);handler.post(()->{archiveDirty=true;if(!destroyed)toast("归档保存失败");});}});
    }catch(Exception e){if(!destroyed)toast("归档保存失败");}
  }
  private String stamp(long value,boolean dateOnly){SimpleDateFormat f=new SimpleDateFormat(dateOnly?"yyyy-MM-dd":"MM-dd HH:mm",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(value));}
  private void load(){try{if(!storage.getBaseFile().exists()||storage.getBaseFile().length()>8*1024*1024)return;JSONObject j=new JSONObject(new String(storage.readFully(),StandardCharsets.UTF_8));savedState.restore(engine,j);compact=savedState.compact;large=savedState.large;unread=savedState.unread;hotMode=savedState.hotMode;}catch(Exception ignored){}}
  private void saveSoon(){handler.removeCallbacks(saveTask);handler.postDelayed(saveTask,350);}
  private void saveNow(){if(storage==null||disk.isShutdown())return;try{savedState.compact=compact;savedState.large=large;savedState.unread=unread;savedState.hotMode=hotMode;byte[] bytes=savedState.json().toString().getBytes(StandardCharsets.UTF_8);disk.execute(()->{FileOutputStream out=null;try{out=storage.startWrite();out.write(bytes);storage.finishWrite(out);}catch(Exception e){if(out!=null)storage.failWrite(out);if(!destroyed)handler.post(()->toast("缓存保存失败，请检查设备剩余空间"));}});}catch(Exception ignored){}}
  @Override protected void onPause(){saveNow();handler.removeCallbacks(archiveSaveTask);saveArchive();super.onPause();}
  @Override protected void onResume(){super.onResume();if(body!=null)refresh(false);}
  @Override protected void onDestroy(){destroyed=true;handler.removeCallbacks(archiveSaveTask);saveArchive();handler.removeCallbacks(saveTask);handler.removeCallbacks(renderTask);saveNow();network.shutdownNow();disk.shutdown();super.onDestroy();}
}
