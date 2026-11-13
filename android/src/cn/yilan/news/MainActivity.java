package cn.yilan.news;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.yilan.news.NewsEngine.*;

/** Native offline-first reader; every network request goes directly to a registered news source. */
public final class MainActivity extends Activity {
  private ReaderTheme palette=ReaderTheme.forMode("day");
  private int ACCENT=palette.accent,INK=palette.ink,MUTED=palette.muted,BACKGROUND=palette.background;
  private Context ui=this;
  private static final int AI_PLATFORM=0,AI_LINUX=1,AI_CATALOG=2,AI_HOT=3,AI_FEATURED=4;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService network=Executors.newFixedThreadPool(4),disk=Executors.newSingleThreadExecutor();
  private final NewsState savedState=new NewsState();
  private final Map<String,Snapshot> snapshots=savedState.snapshots;
  private final Map<String,Snapshot> linuxTabs=savedState.linuxTabs;
  private final Map<String,Boolean> sourceEnabled=savedState.sourceEnabled;
  private final List<Article> favorites=savedState.favorites;
  private final Set<String> read=savedState.read,compare=new LinkedHashSet<>();
  private NewsEngine engine;private JSONObject catalog;private AtomicFile storage,archiveStorage;
  private LinearLayout body,nav;private ScrollView scroll;private ProgressBar progress;private EditText search;
  private int tab=0,aiMode=0,limit=30,hotLimit=10,done=0,total=0,updated=0,failed=0,cached=0;
  private String query="",category="all",provider="all";private boolean busy=false,destroyed=false,compact=false,large=false,unread=false,hotMode=false;
  private Button refresh,linuxUpdateBtn;private boolean linuxUpdating;private int linuxTab=0;
  private final ArticleTextCache linuxFullCache=new ArticleTextCache();
  private ReaderBackdrop backdrop;
  private AiCoordinator aiService;
  private final Map<String,LinearLayout> aiReportCards=new HashMap<>();
  private final Map<TextView,Article> titleViews=new LinkedHashMap<>();private final List<Article> titleCandidates=new ArrayList<>();
  private final Runnable titleRequest=()->{if(!destroyed&&aiService!=null&&!busy&&(search==null||!search.hasFocus()))aiService.ensureTitles(new ArrayList<>(titleCandidates));};
  private boolean keyboardVisible;private Runnable afterDateFetch;
  private final Map<Boolean,String> aiFloors=new HashMap<>();private final Set<String> collapsedAiDays=new HashSet<>();
  private android.animation.ValueAnimator refreshSpin;private android.graphics.drawable.RotateDrawable refreshGlyph;
  private ReaderUpdateTransition updateTransition;private String viewKey="";private boolean changingNavigation;
  private boolean foreground=true;
  private void aiChanged(){if(destroyed)return;updateRefreshControls();updateTitleViews();if(aiService.running)updateAiReports();else render(true);}
  private final Set<String> newsTags=new LinkedHashSet<>(),feedSources=new LinkedHashSet<>(),vendors=new LinkedHashSet<>(),collapsed=new HashSet<>(),historyAttempts=new HashSet<>();
  private final Map<String,String> historyResults=new HashMap<>();
  private String newsRegion="all",feedRegion="all",region="all",toolType="all",pendingDay="";private boolean pendingAI,userScrolling;
  private final Map<Boolean,String> timelineSince=new HashMap<>(),missingDays=new HashMap<>();
  private final Set<String> expandedDates=new HashSet<>(),initializedTimelines=new HashSet<>();

  private final NewsArchive archive=new NewsArchive();
  private List<FeaturedNews.Event> featured=new ArrayList<>();
  private String featuredKey="",builtFeaturedKey="";
  private boolean featuredMode=false,archiveDirty=false,archiveWritable=true;private String archiveLoadError="",archiveSaveError="";
  private final Runnable archiveSaveTask=()->saveArchive();
  private final Runnable saveTask=()->saveNow();
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private GradientDrawable shape(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
  private TextView text(String value,int size,int color,boolean bold){
    TextView t=new TextView(ui);t.setText(value);t.setTextSize((large?1.12f:1f)*Math.max(12,size));t.setTextColor(color);
    t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1.1f);return t;
  }
  private Drawable ripple(Drawable background){return new RippleDrawable(ColorStateList.valueOf((palette.dark?0x2287d4cc:0x1816646d)),background,null);}
  private Drawable floating(boolean selected,int radius){return tab==3?shape(selected?ACCENT:palette.surface,palette.line,radius):new GlassPanelDrawable(palette,selected,dp(radius),getResources().getDisplayMetrics().density);}
  private void styleButton(Button b,boolean selected){
    b.setSelected(selected);b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{b==refresh&&selected?palette.onAccent:MUTED,selected?palette.onAccent:INK}));
    b.setBackgroundTintList(null);b.setBackground(ripple(floating(selected,14)));
  }
  private Button button(String label,boolean selected,Runnable action){
    Button b=new Button(ui);b.setAllCaps(false);b.setText(label);b.setTextSize(large?15:14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),dp(9),dp(14),dp(9));b.setStateListAnimator(null);
    styleButton(b,selected);b.setOnClickListener(v->action.run());return b;
  }
  private LinearLayout vertical(){LinearLayout l=new LinearLayout(ui);l.setOrientation(LinearLayout.VERTICAL);return l;}
  private void gap(LinearLayout parent,int height){parent.addView(new Space(ui),new LinearLayout.LayoutParams(1,dp(height)));}
  private void divider(LinearLayout parent){View line=new View(ui);line.setBackgroundColor(palette.line);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
  private LinearLayout card(){
    LinearLayout c=vertical();c.setPadding(dp(18),dp(17),dp(18),dp(17));c.setBackground(ripple(floating(false,20)));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));body.addView(c,lp);return c;
  }
  private ReaderIcon icon(String name,int color,int size){return new ReaderIcon(name,color,dp(size));}
  private void applyTheme(){
    palette=ReaderTheme.forMode(savedState.themeMode);ACCENT=palette.accent;INK=palette.ink;MUTED=palette.muted;BACKGROUND=palette.background;
    int theme=palette.dark?R.style.Reader_Night:R.style.Reader_Day;setTheme(theme);ui=new ContextThemeWrapper(this,theme);
    getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(palette.surface);
    if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
    int light=palette.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    getWindow().getDecorView().setSystemUiVisibility(light);
    if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null)controller.setSystemBarsAppearance(palette.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
  }
  private AlertDialog.Builder dialog(){
    return new AlertDialog.Builder(ui){@Override public AlertDialog create(){AlertDialog d=super.create();d.setOnShowListener(v->{
      Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(shape(palette.surface,palette.line,24));w.setDimAmount(palette.dark?0.65f:0.3f);w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(24),dp(680)),-2);}
      for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button b=d.getButton(which);if(b!=null){b.setAllCaps(false);b.setTextColor(ACCENT);b.setTextSize(14);b.setMinHeight(dp(48));}}
    });return d;}};
  }
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  private String asset(String name)throws IOException{try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("内置资料过大");out.write(b,0,n);}return out.toString("UTF-8");}}
  @Override public void onCreate(Bundle state){super.onCreate(state);try{engine=new NewsEngine(asset("sources.json"),new LinuxDoTransport(getFilesDir().getAbsolutePath()));catalog=new JSONObject(asset("ai-catalog.json"));storage=new AtomicFile(new File(getFilesDir(),"native-news-v3.json"));for(Source s:engine.sources)sourceEnabled.put(s.id,s.defaultEnabled);load();applyTheme();archiveStorage=new AtomicFile(new File(getFilesDir(),"news-archive-v1.json"));loadArchive();aiService=new AiCoordinator(new AiVault(getFilesDir()),handler,disk,this::aiChanged);aiService.load();chrome();render(false);}catch(Exception e){TextView t=text("应用初始化失败："+NewsEngine.error(e),16,INK,false);t.setPadding(dp(24),dp(50),dp(24),0);setContentView(t);}}
  private void chrome(){
    if(refreshSpin!=null)refreshSpin.cancel();
    if(updateTransition!=null)updateTransition.finish();viewKey="";
    FrameLayout stage=new FrameLayout(ui);backdrop=new ReaderBackdrop(ui);stage.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));LinearLayout root=vertical();stage.addView(root,new FrameLayout.LayoutParams(-1,-1));root.setFocusableInTouchMode(true);
    if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});else root.setFitsSystemWindows(true);
    if(Build.VERSION.SDK_INT<30)root.getViewTreeObserver().addOnGlobalLayoutListener(()->{android.graphics.Rect frame=new android.graphics.Rect();root.getWindowVisibleDisplayFrame(frame);boolean visible=root.getRootView().getHeight()-frame.bottom>dp(160);if(keyboardVisible&&!visible)releaseSearch();keyboardVisible=visible;});
    LinearLayout bar=new LinearLayout(ui);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(20),dp(12),dp(20),dp(8));
    search=new SearchInput(ui,this::releaseSearch);search.setFocusable(false);search.setCursorVisible(false);search.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN){search.setFocusableInTouchMode(true);search.setFocusable(true);search.setCursorVisible(true);search.requestFocus();}return false;});search.setSingleLine(true);search.setHint("搜索新闻、来源");search.setContentDescription("搜索新闻、来源");search.setTextSize(15);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setPadding(dp(14),0,dp(12),0);
    search.setBackgroundTintList(null);search.setBackground(floating(false,16));search.setCompoundDrawablesRelativeWithIntrinsicBounds(icon("search",MUTED,18),null,null,null);search.setCompoundDrawablePadding(dp(9));search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.setText(query);
    search.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);releaseSearch();return true;}return false;});
    LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(48),1);sp.setMargins(0,0,dp(10),0);bar.addView(search,sp);
    refresh=button("更新",true,()->refresh(true));refresh.setContentDescription("一键更新所有已启用来源");refreshGlyph=new android.graphics.drawable.RotateDrawable();refreshGlyph.setDrawable(icon("refresh",palette.onAccent,17));refreshGlyph.setFromDegrees(0);refreshGlyph.setToDegrees(360);refreshGlyph.setPivotX(.5f);refreshGlyph.setPivotY(.5f);refreshGlyph.setPivotXRelative(true);refreshGlyph.setPivotYRelative(true);
    refresh.setCompoundDrawablesRelativeWithIntrinsicBounds(refreshGlyph,null,null,null);refreshSpin=android.animation.ValueAnimator.ofInt(0,10000);refreshSpin.setDuration(900);refreshSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);refreshSpin.setInterpolator(new android.view.animation.LinearInterpolator());refreshSpin.addUpdateListener(a->refreshGlyph.setLevel((Integer)a.getAnimatedValue()));refresh.setCompoundDrawablePadding(dp(5));refresh.setEnabled(!busy);refresh.setEllipsize(TextUtils.TruncateAt.END);bar.addView(refresh,new LinearLayout.LayoutParams(-2,dp(48)));root.addView(bar);updateTransition=new ReaderUpdateTransition(bar,search,refresh,dp(10));
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(changingNavigation)return;query=s.toString().trim();limit=30;hotLimit=10;render(false);}public void afterTextChanged(Editable e){}});
    progress=new ProgressBar(ui,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(ColorStateList.valueOf(ACCENT));progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));progress.setProgressBackgroundTintList(ColorStateList.valueOf(palette.line));progress.setMax(Math.max(total,1));progress.setProgress(done);progress.setIndeterminate(linuxUpdating);progress.setVisibility(busy?View.VISIBLE:View.INVISIBLE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
    scroll=new ScrollView(ui);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
    scroll.setOnTouchListener((v,event)->{userScrolling=event.getActionMasked()==MotionEvent.ACTION_MOVE;return false;});
    scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(!(aiService!=null&&aiService.enabled())&&userScrolling&&body!=null&&y>oldY&&y+scroll.getHeight()>=body.getHeight()-dp(24)&&(tab==0&&featuredMode||tab==1&&aiMode==AI_FEATURED)){userScrolling=false;final boolean ai=tab==1;handler.post(()->showOlder(ai));}});
    body=vertical();body.setPadding(dp(20),dp(16),dp(20),dp(28));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    divider(root);nav=new LinearLayout(ui);nav.setPadding(dp(12),dp(8),dp(12),dp(8));nav.setBackgroundColor(palette.surface);nav.setBaselineAligned(false);nav.setGravity(Gravity.CENTER_VERTICAL);root.addView(nav);setContentView(stage);drawNav();updateRefreshControls();
  }
  private void drawNav(){
    nav.removeAllViews();String[] labels={"新闻","AI 专栏","收藏","设置"},icons={"news","ai","bookmark","settings"};
    for(int i=0;i<labels.length;i++){final int index=i;boolean selected=tab==i;LinearLayout item=vertical();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(3),0,dp(3));item.setBackground(ripple(shape(Color.TRANSPARENT,0,18)));item.setSelected(selected);item.setFocusable(true);item.setMinimumHeight(dp(60));item.setContentDescription(labels[i]+(selected?"，已选中":""));
      ImageView mark=new ImageView(ui);mark.setImageDrawable(icon(icons[i],selected?ACCENT:MUTED,22));mark.setPadding(dp(14),dp(4),dp(14),dp(4));mark.setBackground(shape(selected?palette.soft:Color.TRANSPARENT,0,15));mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(mark,new LinearLayout.LayoutParams(dp(52),dp(30)));gap(item,5);
      TextView label=text(labels[i],12,selected?ACCENT:MUTED,selected);label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LinearLayout.LayoutParams(-2,-2));
      item.setOnClickListener(v->{if(tab==index)return;releaseSearch();tab=index;limit=30;category="all";provider="all";query="";changingNavigation=true;search.setText("");changingNavigation=false;drawNav();render(false);});nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));
    }
  }
  private void render(boolean keepScroll){
    if(body==null||destroyed)return;int y=keepScroll?scroll.getScrollY():0;linuxUpdateBtn=null;aiReportCards.clear();titleViews.clear();titleCandidates.clear();body.removeAllViews();
    if(!motionAllowed()){body.animate().cancel();body.setAlpha(1);body.setTranslationY(0);}
    String next=tab+":"+aiMode+":"+toolType+":"+hotMode+":"+featuredMode;boolean pageChanged=!viewKey.isEmpty()&&!viewKey.equals(next);viewKey=next;
    backdrop.showPage(tab,palette,savedState.backgroundArt);nav.setBackgroundColor(tab==3?palette.surface:palette.dark?0xeb101c2c:0xebf5f9fd);search.setBackground(floating(false,16));styleButton(refresh,true);
    if(tab==3)settings();else if(tab==1)ai();else news();scroll.post(()->scroll.scrollTo(0,y));if(aiService!=null)aiService.visibleTitles(titleCandidates);handler.removeCallbacks(titleRequest);handler.postDelayed(titleRequest,200);
    if(pageChanged&&motionAllowed()&&foreground){body.animate().cancel();body.setAlpha(.35f);body.setTranslationY(dp(6));body.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
  }
  private boolean motionAllowed(){return savedState.motion&&android.animation.ValueAnimator.areAnimatorsEnabled();}
  private LinearLayout chips(LinearLayout parent){HorizontalScrollView h=new HorizontalScrollView(ui);h.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(ui);row.setPadding(0,0,dp(2),0);h.addView(row);parent.addView(h);gap(parent,14);return row;}
  private void chip(LinearLayout row,String label,boolean selected,Runnable click){
    Button b=filterButton(label,click);b.setSelected(selected);b.setTypeface(Typeface.create("sans-serif",selected?Typeface.BOLD:Typeface.NORMAL));
    if(selected)b.setPaintFlags(b.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);b.setPadding(0,dp(9),dp(20),dp(9));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);row.addView(b,lp);
    if(selected)row.post(()->{if(row.getParent() instanceof HorizontalScrollView)((HorizontalScrollView)row.getParent()).scrollTo(Math.max(0,b.getLeft()-dp(12)),0);});
  }
  private Button filterButton(String label,Runnable click){
    Button b=button(label,false,click);b.setBackgroundTintList(null);b.setBackground(new ColorDrawable(Color.TRANSPARENT));b.setTextColor(INK);b.setTextSize(large?15:13);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(0,dp(6),dp(8),dp(6));b.setMinHeight(dp(40));b.setMinimumHeight(dp(40));b.setSingleLine(true);b.setEllipsize(TextUtils.TruncateAt.END);return b;
  }
  private Button sectionButton(String label,Runnable action){Button b=button(label,false,action);b.setTextColor(INK);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setBackground(ripple(floating(false,14)));return b;}
  private void emptyState(String label){LinearLayout c=card();c.setGravity(Gravity.CENTER);c.setPadding(dp(24),dp(32),dp(24),dp(32));ImageView mark=new ImageView(ui);mark.setImageDrawable(icon(tab==2?"bookmark":"news",MUTED,28));c.addView(mark,new LinearLayout.LayoutParams(dp(28),dp(28)));gap(c,16);c.addView(text(label,14,MUTED,false));}
  private boolean match(String value){return query.isEmpty()||value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));}
  private List<Article> articles(boolean ai){List<Article> all=new ArrayList<>();if(tab==2)all.addAll(favorites);else for(Source s:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(s.id))&&(ai?ReadingRules.platformSource(catalog,s):FeaturedNews.latestSource(s))){Snapshot snap=snapshots.get(s.id);if(snap!=null)for(Article item:snap.items)if(ai?ReadingRules.platformArticle(s,item):ReadingRules.newsArticle(s,item))all.add(item);}all.sort((a,b)->{int c=Long.compare(b.published,a.published);return c!=0?c:Integer.compare(a.rank,b.rank);});Set<String> seen=new HashSet<>();List<Article> out=new ArrayList<>();for(Article a:all){if(!match(searchable(a))||unread&&read.contains(a.id))continue;Source source=engine.sourceMap.get(a.source);if(tab==0&&!SourceFilters.news(catalog,source,newsRegion,newsTags))continue;if(ai&&!SourceFilters.platform(catalog,source,feedRegion,feedSources))continue;if(!seen.add(a.url))continue;out.add(a);}return out;}
  private void news(){
    if(tab!=2){
      LinearLayout modes=chips(body);chip(modes,"最新",!hotMode&&!featuredMode,()->setNewsMode(0));chip(modes,"分站热榜",hotMode,()->setNewsMode(1));chip(modes,"精选",featuredMode,()->setNewsMode(2));
      if(featuredMode){featuredNews(false);return;}if(hotMode){hotNews(false);return;}
      LinearLayout row=new LinearLayout(ui);
      row.addView(filterButton(regionLabel(newsRegion)+" ▾",()->pickRegion(newsRegion,next->{newsRegion=next;newsTags.clear();limit=30;render(false);})),new LinearLayout.LayoutParams(0,dp(40),1));
      row.addView(filterButton(newsTags.isEmpty()?"全部标签 ▾":"标签 · "+newsTags.size()+" ▾",()->pickMany("标签",Arrays.asList(SourceFilters.TAGS),Arrays.asList(SourceFilters.TAG_NAMES),newsTags,()->{limit=30;render(false);})),new LinearLayout.LayoutParams(0,dp(40),1));
      body.addView(row);gap(body,10);
    }
    if(tab==0)aiReport(AiEngine.NEWS);showArticles(articles(false));
  }
  private void setNewsMode(int mode){hotMode=mode==1;featuredMode=mode==2;limit=30;hotLimit=10;saveSoon();render(false);}
  private void requestFeatured(boolean ai){
    final Set<String> boards=visibleAIBoards();final String key=ai+":"+boards+":"+archive.revision+":"+sourceEnabled.toString();if(key.equals(featuredKey))return;featuredKey=key;
    final List<Article> input=archive.articles();final Map<String,Boolean> enabled=new LinkedHashMap<>(sourceEnabled);
    disk.execute(()->{List<FeaturedNews.Event> events=FeaturedNews.group(input,engine.sourceMap,enabled,ai,boards);handler.post(()->{if(destroyed||!key.equals(featuredKey))return;featured=events;builtFeaturedKey=key;if(tab==0&&featuredMode||tab==1&&aiMode==AI_FEATURED)render(true);});});
  }
  private String timelineFloor(boolean ai){return timelineSince.getOrDefault(ai,FeaturedNews.initialDay(System.currentTimeMillis()));}
  private void showOlder(boolean ai){
    if(destroyed||!(ai?tab==1&&aiMode==AI_FEATURED:tab==0&&featuredMode)||!featuredKey.equals(builtFeaturedKey))return;
    String scope=ai?"ai:":"news:",missing=missingDays.get(ai);
    if(missing!=null&&!ReadingRules.hasDay(featured,missing))return;
    String floor=timelineFloor(ai),older=null;
    for(FeaturedNews.Event e:featured)if(e.day().compareTo(floor)<0&&(older==null||e.day().compareTo(older)>0))older=e.day();
    if(older!=null)timelineSince.put(ai,FeaturedNews.earlierDay(older));
    else{String day=ReadingRules.olderGap(featured,System.currentTimeMillis());timelineSince.put(ai,day);missingDays.put(ai,day);expandedDates.add(scope+day);handler.post(()->requestHistory(ai,day));}
    limit+=30;render(true);
  }
  private void featuredNews(boolean ai){
    storageWarnings();
    if(aiService!=null&&aiService.enabled()){aiFeatured(ai);return;}
    requestFeatured(ai);body.addView(text("本机已抓来源归并",12,INK,false));gap(body,10);
    if(archive.incomplete)body.addView(text("归档容量已满，部分新条目未保存",12,INK,false));
    if(!featuredKey.equals(builtFeaturedKey)){body.addView(text("正在归并…",12,INK,false));return;}
    String scope=ai?"ai:":"news:";List<FeaturedNews.Event> visible=new ArrayList<>();
    for(FeaturedNews.Event e:featured){boolean matches=false,hasUnread=false;for(Article a:e.reports){matches|=match(searchable(a));hasUnread|=!read.contains(a.id);}if(matches&&(!unread||hasUnread))visible.add(e);}
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
      body.addView(sectionButton(day.getKey()+" · "+day.getValue().size()+(expanded?" ▾":" ▸"),()->{if(!expandedDates.add(key))expandedDates.remove(key);render(true);}));gap(body,10);
      if(!expanded)continue;
      if(day.getValue().isEmpty())body.addView(text(historyResults.getOrDefault(key,"正在检查该日期…"),12,INK,false));
      total+=day.getValue().size();
      for(FeaturedNews.Event e:day.getValue()){
        if(count++>=limit)continue;LinearLayout c=card();TextView title=text(e.title(),17,INK,true);
        boolean allRead=true;for(Article a:e.reports)allRead&=read.contains(a.id);title.setAlpha(allRead?0.78f:1f);title.setOnClickListener(v->openEvent(e));c.setOnClickListener(v->openEvent(e));c.addView(title);gap(c,5);c.addView(text(e.publishers.size()+" 家来源 · "+stamp(e.latest,false),11,MUTED,false));
      }
    }
    if(total>limit){body.addView(button("再看 30 条",false,()->{limit+=30;render(true);}));return;}
    if(missing==null)body.addView(button("更早日期",false,()->showOlder(ai)));
  }
  private void openEvent(FeaturedNews.Event event){
    LinearLayout content=vertical();content.setPadding(dp(20),dp(20),dp(20),dp(12));content.addView(text("本机已抓来源归并 · "+event.publishers.size()+" 家来源",12,MUTED,false));gap(content,16);
    for(Article a:event.reports){
      read.add(a.id);LinearLayout report=vertical();report.setPadding(dp(16),dp(16),dp(16),dp(16));report.setBackground(shape(palette.raised,palette.line,16));
      TextView title=text(a.title,18,INK,true);title.setOnClickListener(v->open(a));report.addView(title);gap(report,8);report.addView(text(a.sourceName+" · "+publication(a),12,MUTED,false));
      if(!a.summary.isEmpty()){gap(report,14);report.addView(text(a.summary,15,INK,false));}gap(report,14);report.addView(smallButton("原文  ↗",()->external(a.url)));content.addView(report);gap(content,12);
    }
    while(read.size()>2000)read.remove(read.iterator().next());saveSoon();render(true);ScrollView view=new ScrollView(ui);view.addView(content);dialog().setView(view).setPositiveButton("关闭",null).show();
  }
  private void hotNews(boolean ai){
    if(!ai)aiReport(AiEngine.HOT);
    boolean more=false;Set<String> aiURLs=NewsEngine.aiHotURLs(snapshots,sourceEnabled);
    for(String id:NewsEngine.HOT_SOURCES){
      Source source=engine.sourceMap.get(id);if(source==null||!Boolean.TRUE.equals(sourceEnabled.get(id))||(!ai&&!FeaturedNews.stationSource(id))||(ai&&!ReadingRules.aiBoard(id,snapshots.get(id))))continue;
      Snapshot snapshot=snapshots.get(id);List<Article> items=FeaturedNews.hotItems(id,snapshot,ai,aiURLs);
      items.removeIf(a->!match(searchable(a))||unread&&read.contains(a.id));
      if(ai&&items.isEmpty())continue;
      String boardKey=(ai?"ai:":"news:")+id;body.addView(sectionButton(source.name+" · "+items.size()+(collapsed.contains(boardKey)?" ▸":" ▾"),()->{if(!collapsed.add(boardKey))collapsed.remove(boardKey);render(true);}));gap(body,10);if(collapsed.contains(boardKey))continue;
      for(int i=0;i<Math.min(hotLimit,items.size());i++)articleCard(items.get(i));
      more|=items.size()>hotLimit;gap(body,18);
    }
    if(more)body.addView(button("每榜再看 10 条",false,()->{hotLimit+=10;render(true);}));
  }
  private void showArticles(List<Article> list){
    if(list.isEmpty())emptyState(tab==2?"暂无符合条件的收藏":"暂无符合条件的条目");
    for(int i=0;i<Math.min(limit,list.size());i++)articleCard(list.get(i));
    if(list.size()>limit)body.addView(button("再看 30 条",false,()->{limit+=30;render(true);}));
  }
  private void articleCard(Article a){
    LinearLayout c=card();TextView title=text(listTitle(a),17,INK,true);titleViews.put(title,a);titleCandidates.add(a);title.setAlpha(read.contains(a.id)?0.78f:1f);
    title.setOnClickListener(v->open(a));c.setOnClickListener(v->open(a));c.addView(title);gap(c,5);c.addView(text(a.sourceName+" · "+publication(a),11,MUTED,false));
  }
  private String searchable(Article a){String translated=aiService!=null&&aiService.enabled()?aiService.titles.get(a):null;return a.title+" "+a.summary+" "+a.sourceName+(translated==null?"":" "+translated);}
  private String listTitle(Article a){if(aiService==null||!aiService.enabled()||!AiEngine.foreign(a.title))return a.title;String translated=aiService.titles.get(a);return translated!=null?translated:aiService.errors.containsKey("titles")?a.title:"正在翻译标题…";}
  private void updateTitleViews(){for(Map.Entry<TextView,Article> entry:titleViews.entrySet())entry.getKey().setText(listTitle(entry.getValue()));}
  private boolean isFavorite(String id){for(Article a:favorites)if(a.id.equals(id))return true;return false;}
  private void open(Article a){
    read.add(a.id);while(read.size()>2000)read.remove(read.iterator().next());saveSoon();render(true);
    final boolean linux="linuxdo".equals(a.source);
    LinearLayout content=vertical();content.setPadding(dp(22),dp(22),dp(22),dp(14));content.addView(text(a.title,23,INK,true));gap(content,16);
    content.addView(text(a.sourceName+" · "+(a.published>0?stamp(a.published,a.dateOnly):"未提供发布时间"),12,MUTED,false));
    if(NewsEngine.HOT_SOURCES.contains(a.source)){gap(content,10);content.addView(text("热度："+NewsEngine.heatLabel(a),13,palette.warm,true));}
    Snapshot snapshot=snapshotOf(a);if(snapshot!=null&&!snapshot.error.isEmpty()){gap(content,8);content.addView(text("历史快照",12,MUTED,false));}
    gap(content,20);
    final TextView bodyView=text(linux?"正在获取全文…":a.summary,16,INK,false);content.addView(bodyView);
    final String[] original={a.summary};final boolean[] loading={linux};
    final TextView translated=text("",15,INK,false);translated.setVisibility(View.GONE);content.addView(translated);
    final Button translate=button("翻译",false,()->{});final boolean[] translatedMode={false};translate.setVisibility(AiEngine.foreign(a.title+"\n"+a.summary)?View.VISIBLE:View.GONE);translate.setEnabled(!linux);content.addView(translate);
    gap(content,24);divider(content);gap(content,16);LinearLayout actions=new LinearLayout(ui);
    Button star=button(isFavorite(a.id)?"已收藏":"收藏",isFavorite(a.id),()->{});
    star.setOnClickListener(v->{if(isFavorite(a.id))favorites.removeIf(x->x.id.equals(a.id));else{favorites.add(0,a);if(favorites.size()>500)favorites.remove(500);}star.setText(isFavorite(a.id)?"已收藏":"收藏");styleButton(star,isFavorite(a.id));saveSoon();drawNav();render(true);});
    for(Button action:new Button[]{star,button("分享",false,()->share(a.title+"\n"+a.url,"分享新闻")),button("原文",true,()->external(a.url))}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),0,dp(3),0);actions.addView(action,lp);}content.addView(actions);
    ScrollView reader=new ScrollView(ui);reader.addView(content);
    final AlertDialog preview=dialog().setView(reader).setPositiveButton("关闭",null).create();
    preview.show();
    translate.setOnClickListener(v->{
      if(translatedMode[0]){translatedMode[0]=false;translated.setText("");translated.setVisibility(View.GONE);translate.setText("翻译");return;}
      if(loading[0])return;String sourceText=a.title+"\n\n"+original[0];if(!AiEngine.foreign(sourceText)){toast("当前预览并非纯外文");return;}
      translate.setEnabled(false);translate.setText("正在翻译…");translated.setVisibility(View.VISIBLE);translated.setText("正在翻译…");
      aiService.translate(sourceText,value->{if(!destroyed&&preview.isShowing()){translated.setText("译文（AI）\n"+value);translatedMode[0]=true;translate.setText("取消翻译");translate.setEnabled(true);}},reason->{if(!destroyed&&preview.isShowing()){translated.setText(reason);translate.setText("翻译");translate.setEnabled(true);}});
    });
    if(linux){
      final Source src=engine.sourceMap.get("linuxdo");final long topicId=topicIdFromUrl(a.url);final String fallback=a.summary;
      String cachedText=linuxFullCache.get(a.id);
      if(cachedText!=null){bodyView.setText(cachedText);original[0]=cachedText;loading[0]=false;translate.setEnabled(true);translate.setVisibility(AiEngine.foreign(a.title+"\n"+cachedText)?View.VISIBLE:View.GONE);}
      else if(src==null||topicId<=0){bodyView.setText(fallback+"\n\n无法识别帖子编号");loading[0]=false;translate.setEnabled(true);}
      else network.execute(()->{
        try{
          final String full=engine.fetchLinuxTopic(src,topicId);
          handler.post(()->{if(destroyed)return;linuxFullCache.put(a.id,full);if(preview.isShowing()){bodyView.setText(full);original[0]=full;loading[0]=false;translate.setEnabled(true);translate.setVisibility(AiEngine.foreign(a.title+"\n"+full)?View.VISIBLE:View.GONE);}});
        }catch(Exception e){final String reason=NewsEngine.error(e);
          handler.post(()->{if(!destroyed&&preview.isShowing()){bodyView.setText((fallback.isEmpty()?"":fallback+"\n\n")+"全文获取失败："+reason);loading[0]=false;translate.setEnabled(true);}});}
      });
    }
  }
  /** Resolve the snapshot holding an article: ordinary map, or any linux.do sub-page. */
  private Snapshot snapshotOf(Article a){
    if(!"linuxdo".equals(a.source))return snapshots.get(a.source);
    for(Snapshot s:linuxTabs.values())for(Article x:s.items)if(x.id.equals(a.id))return s;
    return null;
  }
  /** Extract the numeric topic id from a /t/topic/<id> or /t/<id> URL. */
  private long topicIdFromUrl(String url){
    Matcher m=Pattern.compile("/t/(?:topic/)?([0-9]+)").matcher(url);
    try{return m.find()?Long.parseLong(m.group(1)):-1;}catch(NumberFormatException invalid){return -1;}
  }

  private boolean external(String url){String safe=NewsEngine.publicURL(url);if(safe==null){toast("已阻止非网页、本地网络或可执行文件链接");return false;}try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE));return true;}catch(ActivityNotFoundException e){toast("请安装可用的浏览器以阅读原文");return false;}}
  private void share(String value,String title){Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value);try{startActivity(Intent.createChooser(i,title));}catch(ActivityNotFoundException e){toast("未找到可分享的应用");}}
  private void ai(){
    LinearLayout modes=chips(body);String[] names={"平台动态","linux.do","AI","分站热榜","精选"};
    for(int i=0;i<names.length;i++){final int mode=i;chip(modes,names[i],aiMode==i,()->{aiMode=mode;limit=30;hotLimit=10;render(false);});}
    if(aiMode==AI_HOT){hotNews(true);return;}if(aiMode==AI_FEATURED){featuredNews(true);return;}
    if(aiMode==AI_LINUX){linuxdoPage();return;}
    if(aiMode==AI_PLATFORM){sourceFilters();aiReport(AiEngine.AI);showArticles(articles(true));return;}
    LinearLayout kinds=chips(body);String[] ids={"all","product","harness","sdk","model"},labels={"全部","产品","Harness","SDK","模型"};
    for(int i=0;i<ids.length;i++){final String id=ids[i];chip(kinds,labels[i],toolType.equals(id),()->{toolType=id;render(false);});}
    platformFilters();if(toolType.equals("model")){modelPage();return;}
    JSONArray tools=catalog.optJSONArray("tools");
    for(int i=0;tools!=null&&i<tools.length();i++){JSONObject t=tools.optJSONObject(i);if(t==null||!ReadingRules.provider(catalog,t.optString("provider"),region,vendors)||!match(t.optString("name")+" "+t.optString("description")+" "+t.optString("role")))continue;
      JSONArray k=t.optJSONArray("kinds");boolean product=hasKind(k,"cli")||hasKind(k,"ide")||hasKind(k,"desktop")||hasKind(k,"web");
      if(toolType.equals("product")&&!product||toolType.equals("harness")&&!hasKind(k,"harness")||toolType.equals("sdk")&&!hasKind(k,"sdk")&&!hasKind(k,"framework"))continue;toolCard(t);
    }
    if(toolType.equals("all")){gap(body,6);body.addView(text("模型",16,INK,true));gap(body,12);modelPage();}
  }
  private interface RegionChanged{void accept(String region);}
  private String regionLabel(String selected){return "china".equals(selected)?"国内平台":"global".equals(selected)?"国外平台":"全部平台";}
  private void pickRegion(String selected,RegionChanged change){dialog().setTitle("平台范围").setSingleChoiceItems(new String[]{"全部平台","国内平台","国外平台"},"china".equals(selected)?1:"global".equals(selected)?2:0,(d,w)->{d.dismiss();change.accept(new String[]{"all","china","global"}[w]);}).show();}
  private void sourceFilters(){
    LinearLayout row=new LinearLayout(ui);
    row.addView(filterButton(regionLabel(feedRegion)+" ▾",()->pickRegion(feedRegion,next->{feedRegion=next;feedSources.clear();limit=30;render(false);})),new LinearLayout.LayoutParams(0,dp(40),1));
    row.addView(filterButton(feedSources.isEmpty()?"全部来源 ▾":"来源 · "+feedSources.size()+" ▾",()->{
      List<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("all");names.add("全部来源");
      for(Source source:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(source.id))&&ReadingRules.platformChoice(catalog,source,snapshots.get(source.id))&&SourceFilters.inRegion(catalog,source,feedRegion)){ids.add(source.id);names.add(source.name);}
      pickMany("消息来源",ids,names,feedSources,()->{limit=30;render(false);});
    }),new LinearLayout.LayoutParams(0,dp(40),1));body.addView(row);gap(body,10);
  }
  private void linuxdoPage(){
    Source source=engine.sourceMap.get("linuxdo");
    if(source==null||!Boolean.TRUE.equals(sourceEnabled.get("linuxdo"))){emptyState("LINUX DO 来源未开启，请在「设置-信息来源」中开启");return;}
    final String[] names={"默认更新","浏览量","最新"};
    // Left: option dropdown; right: refresh button for the chosen sub-page.
    LinearLayout bar=new LinearLayout(ui);bar.setGravity(Gravity.CENTER_VERTICAL);
    Button option=filterButton(names[linuxTab]+" ▾",()->dialog().setTitle("选项").setItems(names,(d,w)->{linuxTab=w;render(false);}).show());
    option.setContentDescription("选择 LINUX DO 子页");option.setEnabled(!busy);
    bar.addView(option,new LinearLayout.LayoutParams(0,dp(46),1));
    linuxUpdateBtn=button("更新",false,this::refreshLinuxTab);
    linuxUpdateBtn.setContentDescription("更新当前 LINUX DO 子页");linuxUpdateBtn.setEnabled(!busy);
    bar.addView(linuxUpdateBtn,new LinearLayout.LayoutParams(-2,dp(46)));
    body.addView(bar);
    gap(body,10);
    Snapshot snap=linuxTabs.get(linuxTabKey(linuxTab));
    if(snap!=null&&!snap.error.isEmpty()){
      String summary=snap.error.contains("HTTP 403")?"本站暂时拒绝访问（403）":snap.error.contains("访问验证")?"本站要求访问验证":"本次更新失败";
      TextView error=text(summary+" · 已保留 "+snap.items.size()+" 条，点击查看错误详情",12,palette.danger,false);
      error.setOnClickListener(v->dialog().setTitle("LINUX DO · "+names[linuxTab]).setMessage(snap.error).setPositiveButton("关闭",null).show());body.addView(error);gap(body,10);
    }
    List<Article> items=new ArrayList<>();
    if(snap!=null)for(Article a:snap.items)if(match(searchable(a))&&(!unread||!read.contains(a.id)))items.add(a);
    showArticles(items);
  }
  private String linuxTabKey(int m){return m==1?"views":m==2?"latest":"default";}
  private Button smallButton(String label,Runnable click){Button b=button(label,false,click);b.setTextSize(large?14:12);b.setMinHeight(dp(36));b.setMinimumHeight(dp(36));b.setPadding(dp(10),dp(4),dp(10),dp(4));b.setTextColor(ACCENT);b.setBackground(ripple(floating(false,12)));return b;}
  private void pickMany(String title,List<String> ids,List<String> names,Set<String> selected,Runnable done){
    final boolean[] syncing={false};Set<String> next=new LinkedHashSet<>(selected);boolean[] checked=new boolean[ids.size()];
    ReadingRules.syncChoices(ids,next,checked);
    dialog().setTitle(title).setMultiChoiceItems(names.toArray(new String[0]),checked,(dialog,index,on)->{
      if(syncing[0])return;ReadingRules.choose(next,ids.get(index),on);ListView list=((AlertDialog)dialog).getListView();
      syncing[0]=true;try{ReadingRules.syncChoices(ids,next,checked);for(int i=0;i<ids.size();i++)list.setItemChecked(i,checked[i]);}finally{syncing[0]=false;}
    }).setPositiveButton("确定",(d,w)->{selected.clear();selected.addAll(next);done.run();}).setNegativeButton("取消",null).show();
  }
  private void platformFilters(){
    LinearLayout row=new LinearLayout(ui);String label=region.equals("china")?"国内平台":region.equals("global")?"国外平台":"全部平台";
    row.addView(filterButton(label+" ▾",()->dialog().setItems(new String[]{"全部","国外平台","国内平台"},(d,w)->{region=new String[]{"all","global","china"}[w];vendors.clear();render(false);}).show()),new LinearLayout.LayoutParams(0,dp(40),1));
    row.addView(filterButton(vendors.isEmpty()?"全部厂商 ▾":"厂商 · "+vendors.size()+" ▾",()->{
      LinkedHashMap<String,String> choices=new LinkedHashMap<>();choices.put("all","全部");JSONArray ps=catalog.optJSONArray("providers");
      for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&ReadingRules.provider(catalog,p.optString("id"),region,Collections.emptySet()))choices.put(p.optString("id"),p.optString("name"));}
      JSONObject data=catalog.optJSONObject("modelData");ps=data==null?null:data.optJSONArray("profiles");
      for(int i=0;ps!=null&&i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null&&ReadingRules.provider(catalog,p.optString("id"),region,Collections.emptySet()))choices.put(p.optString("id"),p.optString("name"));}
      pickMany("厂商",new ArrayList<>(choices.keySet()),new ArrayList<>(choices.values()),vendors,()->render(false));
    }),new LinearLayout.LayoutParams(0,dp(40),1));((LinearLayout.LayoutParams)row.getChildAt(0).getLayoutParams()).setMarginEnd(dp(8));body.addView(row);gap(body,10);
  }

  private Set<String> visibleAIBoards(){Set<String> out=new HashSet<>();for(String id:NewsEngine.HOT_SOURCES)if(Boolean.TRUE.equals(sourceEnabled.get(id))&&ReadingRules.aiBoard(id,snapshots.get(id)))out.add(id);return out;}
  private boolean matchesProvider(Article a){return ReadingRules.provider(catalog,a.provider,region,vendors);}
  private void requestHistory(boolean ai,String day){
    if(day.isEmpty()||destroyed)return;String key=(ai?"ai:":"news:")+day;if(historyAttempts.contains(key))return;
    if(busy){pendingDay=day;pendingAI=ai;return;}historyAttempts.add(key);
    List<Source> selected=new ArrayList<>();for(Source s:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(s.id))&&(ai?(ReadingRules.platformSource(catalog,s)||NewsEngine.HOT_SOURCES.contains(s.id)):FeaturedNews.latestSource(s)))selected.add(s);
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
    long checked=ReadingRules.latestModelCheck(catalog,engine,snapshots);body.addView(text(checked>0?"最近检查成功 "+stamp(checked,false):"最近检查：尚无成功记录",12,INK,false));gap(body,10);
    for(Article a:ReadingRules.newModelNotices(catalog,engine,snapshots))if(matchesProvider(a)){LinearLayout c=card();TextView notice=text(a.title+" · 新发布，尚未写入资料",15,INK,true);notice.setOnClickListener(v->open(a));c.addView(notice);c.addView(text(a.sourceName+" · "+publication(a),11,MUTED,false));}
    if(!compare.isEmpty()){body.addView(button("模型对比 · "+compare.size(),true,()->compareModels()));body.addView(smallButton("清空对比",()->{compare.clear();render(true);}));}
    for(JSONObject m:ReadingRules.sortedModels(modelData().optJSONArray("models"))){if(m==null||!ReadingRules.provider(catalog,m.optString("provider"),region,vendors)||!match(m.optString("name")+" "+m.optString("summary")))continue;
      JSONObject profile=modelProfile(m.optString("provider"));LinearLayout c=card();TextView name=text(m.optString("name"),19,INK,true);c.setOnClickListener(v->modelDetails(m));name.setOnClickListener(v->modelDetails(m));c.addView(name);c.addView(text(profile.optString("name")+" · "+modelRelease(m),11,MUTED,false));gap(c,8);
      TextView features=text("特点："+m.optString("summary"),13,INK,false);features.setMaxLines(3);features.setEllipsize(TextUtils.TruncateAt.END);c.addView(features);String id=m.optString("id");gap(c,14);c.addView(smallButton(compare.contains(id)?"已加入对比 ✓":"加入对比 ＋",()->{if(compare.contains(id))compare.remove(id);else if(compare.size()<3)compare.add(id);else toast("最多对比 3 项");render(true);}));
    }
  }
  private void modelDetails(JSONObject m){
    LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(12));JSONObject profile=modelProfile(m.optString("provider"));
    content.addView(text("发布日期 "+modelRelease(m)+" · "+confidence(m),12,MUTED,false));gap(content,18);content.addView(text("特点",16,ACCENT,true));gap(content,10);content.addView(text(m.optString("summary"),15,INK,false));
    if(!m.optString("analysis").isEmpty()){gap(content,20);content.addView(text("分析",16,ACCENT,true));gap(content,10);content.addView(text(m.optString("analysis"),15,INK,false));}
    gap(content,20);divider(content);gap(content,18);content.addView(text("厂商优势",16,INK,true));gap(content,10);content.addView(text(claims(profile,"strengths"),14,INK,false));gap(content,20);content.addView(text("厂商劣势",16,INK,true));gap(content,10);content.addView(text(claims(profile,"weaknesses"),14,INK,false));gap(content,20);content.addView(text("出处",16,ACCENT,true));gap(content,10);
    Set<String> refs=new LinkedHashSet<>();JSONArray ids=m.optJSONArray("sourceIds");for(int i=0;ids!=null&&i<ids.length();i++)refs.add(ids.optString(i));
    for(String key:new String[]{"strengths","weaknesses"}){JSONArray list=profile.optJSONArray(key);for(int i=0;list!=null&&i<list.length();i++){JSONObject claim=list.optJSONObject(i);ids=claim==null?null:claim.optJSONArray("sourceIds");for(int j=0;ids!=null&&j<ids.length();j++)refs.add(ids.optString(j));}}
    JSONArray sources=modelData().optJSONArray("sources");for(int i=0;sources!=null&&i<sources.length();i++){JSONObject source=sources.optJSONObject(i);if(source!=null&&refs.contains(source.optString("id")))content.addView(smallButton(source.optString("name"),()->external(source.optString("url"))));}
    ScrollView view=new ScrollView(ui);view.addView(content);dialog().setTitle(m.optString("name")).setView(view).setPositiveButton("关闭",null).show();
  }
  private void compareModels(){
    JSONArray models=modelData().optJSONArray("models");TableLayout table=new TableLayout(ui);String[] fields={"模型","特点","厂商优势","厂商劣势"};
    for(int row=0;row<fields.length;row++){TableRow tr=new TableRow(ui);tr.addView(text(fields[row],12,ACCENT,true),new TableRow.LayoutParams(dp(80),-2));
      for(int i=0;models!=null&&i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m==null||!compare.contains(m.optString("id")))continue;JSONObject p=modelProfile(m.optString("provider"));String value=row==0?m.optString("name"):row==1?m.optString("summary")+" ["+confidence(m)+"]":claims(p,row==2?"strengths":"weaknesses");TextView t=text(value,row==0?16:14,row==0?ACCENT:INK,row==0);t.setPadding(dp(14),dp(14),dp(14),dp(14));t.setBackground(shape(row==0?palette.soft:palette.raised,palette.line,12));tr.addView(t,new TableRow.LayoutParams(dp(230),-2));}table.addView(tr);
    }
    HorizontalScrollView horizontal=new HorizontalScrollView(ui);horizontal.addView(table);ScrollView view=new ScrollView(ui);view.addView(horizontal);dialog().setTitle("模型对比 · "+modelData().optString("asOf")).setView(view).setPositiveButton("关闭",null).show();
  }
  private static boolean hasKind(JSONArray a,String value){for(int i=0;a!=null&&i<a.length();i++)if(value.equals(a.optString(i)))return true;return false;}
  private String providerName(String id){JSONArray a=catalog.optJSONArray("providers");for(int i=0;a!=null&&i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p!=null&&id.equals(p.optString("id")))return p.optString("name");}return id;}
  private String types(JSONArray a){List<String> names=new ArrayList<>();Map<String,String> map=new HashMap<>();map.put("desktop","桌面");map.put("ide","IDE / 插件");map.put("cli","CLI");map.put("web","Web");map.put("harness","Harness");map.put("sdk","SDK");map.put("framework","通用框架");for(int i=0;a!=null&&i<a.length();i++)names.add(map.getOrDefault(a.optString(i),a.optString(i)));return String.join(" · ",names);}
  private void toolCard(JSONObject t){
    LinearLayout c=card();c.setOnClickListener(v->toolDetails(t));c.addView(text(providerName(t.optString("provider")),12,palette.warm,true));gap(c,8);c.addView(text(t.optString("name"),19,INK,true));gap(c,8);
    String open=t.optString("open");c.addView(text(types(t.optJSONArray("kinds"))+" · "+("open".equals(open)?"开放源码":"mixed".equals(open)?"部分组件开源":"未确认核心开源"),12,ACCENT,false));gap(c,12);
    TextView description=text(t.optString("description"),14,INK,false);description.setMaxLines(3);description.setEllipsize(TextUtils.TruncateAt.END);c.addView(description);
    Snapshot release=engine.toolSnapshot(t,snapshots);Article recentItem=NewsEngine.latestItem(release);
    if(recentItem!=null){gap(c,14);divider(c);gap(c,12);TextView recent=text(recentItem.title,13,INK,false);recent.setMaxLines(2);recent.setEllipsize(TextUtils.TruncateAt.END);recent.setOnClickListener(v->open(recentItem));c.addView(recent);gap(c,5);c.addView(text(recentItem.sourceName+" · "+publication(recentItem),12,MUTED,false));}
    if(release!=null&&release.checked>0){gap(c,6);c.addView(text("检查 "+stamp(release.checked,false),12,MUTED,false));}
    gap(c,12);c.addView(text("查看详情  ›",12,ACCENT,true));
  }
  private void toolDetails(JSONObject t){LinearLayout content=vertical();content.setPadding(dp(22),dp(14),dp(22),dp(10));content.addView(text(t.optString("description"),15,INK,false));gap(content,12);content.addView(text("出品方："+providerName(t.optString("provider"))+"\n类型："+types(t.optJSONArray("kinds"))+"\n定位："+t.optString("role")+"\n许可："+t.optString("license")+"\n适用平台："+t.optJSONArray("platforms")+"\n工具资料核对于 "+catalog.optString("checkedAt"),13,MUTED,false));if(!t.optString("note").isEmpty()){gap(content,12);content.addView(text(t.optString("note"),13,ACCENT,false));}gap(content,12);JSONArray refs=t.optJSONArray("references");for(int i=0;refs!=null&&i<refs.length();i++){final String url=refs.optString(i);content.addView(button("官方依据 "+(i+1)+" ↗",false,()->external(url)));gap(content,6);}content.addView(button("前往官方产品 / 文档 ↗",true,()->external(t.optString("url"))));ScrollView s=new ScrollView(ui);s.addView(content);dialog().setTitle(t.optString("name")).setView(s).setPositiveButton("关闭",null).show();}
  private void settings(){
    storageWarnings();
    settingsHeading("外观","sun","");LinearLayout appearance=card();appearance.setTag("settings-appearance");LinearLayout options=new LinearLayout(ui);
    themeOption(options,"day","日间","sun");themeOption(options,"night","夜间","moon");appearance.addView(options);gap(appearance,12);divider(appearance);
    toggle(appearance,"背景插画",savedState.backgroundArt,value->{savedState.backgroundArt=value;saveSoon();render(true);});divider(appearance);
    toggle(appearance,"界面动效",savedState.motion,value->{savedState.motion=value;saveSoon();if(updateTransition!=null)updateTransition.finish();updateRefreshControls();render(true);});divider(appearance);
    toggle(appearance,"大字显示",large,value->{large=value;saveSoon();render(true);});divider(appearance);toggle(appearance,"只看未读",unread,value->{unread=value;saveSoon();render(true);});
    boolean enabled=aiService!=null&&aiService.enabled();settingsHeading("AI 功能","ai",enabled?"已启用":"未启用");LinearLayout aiCard=card();aiCard.setTag("settings-ai");
    aiCard.addView(text(aiService!=null&&aiService.config!=null?aiService.config.model:"未配置接口",18,INK,true));gap(aiCard,16);
    LinearLayout aiActions=new LinearLayout(ui);Button configure=button("接口与模型",true,this::aiSettings);aiActions.addView(configure,new LinearLayout.LayoutParams(0,dp(46),1));Button chat=button("与 AI 对话",false,()->aiSettings(true));chat.setEnabled(enabled);LinearLayout.LayoutParams chatLayout=new LinearLayout.LayoutParams(0,dp(46),1);chatLayout.leftMargin=dp(10);aiActions.addView(chat,chatLayout);aiCard.addView(aiActions);
    settingsHeading("缓存管理","archive","");LinearLayout cache=card();cache.setTag("settings-cache");Map<String,Integer> dateCounts=cacheDates();int articles=0;for(int count:ContentDates.counts(cachedArticles()).values())articles+=count;
    cache.addView(text(dateCounts.size()+" 个日期 · "+articles+" 条本地报道",17,INK,true));gap(cache,14);cache.addView(button("打开缓存日历",false,this::cacheCalendar),new LinearLayout.LayoutParams(-1,dp(46)));
    settingsHeading("消息来源","news",enabledCount()+" / "+engine.sources.size()+" 已启用");
    LinearLayout group=card();group.setTag("settings-sources");group.setPadding(dp(10),dp(10),dp(10),dp(2));
    List<Source> sources=SourceFilters.settingsOrder(engine.sources,snapshots,savedState.latestLinuxSnapshot());
    for(Source source:sources){
      LinearLayout c=vertical();c.setPadding(dp(12),dp(6),dp(12),dp(10));c.setBackground(shape(palette.raised,palette.line,12));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(8);group.addView(c,cp);
      LinearLayout row=new LinearLayout(ui);row.setGravity(Gravity.CENTER_VERTICAL);
      TextView name=text(source.name+(source.experimental?" · 实验":"")+" ↗",14,INK,true);name.setGravity(Gravity.CENTER_VERTICAL);name.setMinHeight(dp(40));name.setContentDescription("打开"+source.name+"网站");name.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT)));name.setOnClickListener(v->external(source.home));row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
      Switch sw=new Switch(ui);styleSwitch(sw);sw.setPadding(dp(8),0,0,0);sw.setMinHeight(dp(40));sw.setContentDescription("启用"+source.name);sw.setChecked(Boolean.TRUE.equals(sourceEnabled.get(source.id)));sw.setOnCheckedChangeListener((v,checked)->{sourceEnabled.put(source.id,checked);saveSoon();if("linuxdo".equals(source.id))updateRefreshControls();});row.addView(sw,new LinearLayout.LayoutParams(-2,dp(40)));c.addView(row);
      Snapshot snap="linuxdo".equals(source.id)?savedState.latestLinuxSnapshot():snapshots.get(source.id);
      String value=snap==null?"尚未获取":(!snap.error.isEmpty()?"获取失败 · 保留 "+snap.items.size()+" 条":"已获取 "+snap.items.size()+" 条")+" · "+(snap.fetched>0?stamp(snap.fetched,false):"尚无成功快照");
      c.addView(text(("linuxdo".equals(source.id)?"最近子页 · ":"")+value,12,MUTED,false));
      if(snap!=null&&!snap.error.isEmpty()){
        TextView error=text(snap.error,12,palette.danger,false);error.setMaxLines(3);error.setEllipsize(TextUtils.TruncateAt.END);error.setContentDescription("查看"+source.name+"完整错误");error.setOnClickListener(v->dialog().setTitle(source.name).setMessage(snap.error).setPositiveButton("关闭",null).show());gap(c,3);c.addView(error);
      }
    }
    Button share=filterButton("分享安装包 ↗",this::shareApk);share.setGravity(Gravity.CENTER);body.addView(share,new LinearLayout.LayoutParams(-1,dp(48)));
  }
  private void settingsHeading(String title,String glyph,String status){LinearLayout row=new LinearLayout(ui);row.setGravity(Gravity.CENTER_VERTICAL);row.setTag("settings-heading");ImageView mark=new ImageView(ui);mark.setImageDrawable(icon(glyph,ACCENT,19));mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(mark,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView label=text(title,16,INK,true);label.setPadding(dp(8),0,0,0);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));if(!status.isEmpty())row.addView(text(status,12,MUTED,false));body.addView(row);gap(body,10);}
  private List<Article> cachedArticles(){List<Article> rows=archive.articles();for(Snapshot s:snapshots.values())rows.addAll(s.items);for(Snapshot s:linuxTabs.values())rows.addAll(s.items);if(aiService!=null)for(AiEngine.Result r:aiService.results.values())for(AiEngine.Group g:r.groups)rows.addAll(g.reports);return rows;}
  private Map<String,Integer> cacheDates(){Map<String,Integer> dates=ContentDates.counts(cachedArticles());if(aiService!=null){for(String day:aiService.titles.dates())dates.putIfAbsent(day,1);for(String key:aiService.results.keySet()){String day=ContentDates.date(key);if(!day.isEmpty())dates.putIfAbsent(day,1);}}return dates;}
  private void cacheCalendar(){if(busy||aiService!=null&&(aiService.running||aiService.loading||aiService.saving)){toast("请等待更新完成后清理");return;}CacheCalendarDialog.show(ui,palette,cacheDates(),chosen->clearContentDates(chosen::contains),date->clearContentDates(day->day.compareTo(date)<0));}
  private void storageWarnings(){
    if(!archiveLoadError.isEmpty()){body.addView(text(archiveLoadError,12,palette.danger,false));body.addView(filterButton("重试读取新闻归档",()->{loadArchive();render(true);}));}
    if(!archiveSaveError.isEmpty()){body.addView(text(archiveSaveError,12,palette.danger,false));body.addView(filterButton("重试保存新闻归档",()->{saveArchive();}));}
    if(aiService!=null&&aiService.resultsBlocked){body.addView(text(aiService.loadError,12,palette.danger,false));body.addView(filterButton("重试读取 AI 记录",()->{if(!busy&&!aiService.running&&!aiService.loading&&!aiService.saving)aiService.load();}));}
    if(aiService!=null&&!aiService.saveError.isEmpty()){body.addView(text(aiService.saveError,12,palette.danger,false));body.addView(filterButton("重试保存 AI 记录",()->aiService.retrySave()));}
  }
  private void clearContentDates(java.util.function.Predicate<String> dates){
    if(busy||aiService!=null&&(aiService.running||aiService.loading||aiService.saving)){toast("更新进行中，暂未清理");return;}
    if(!archiveWritable||aiService!=null&&aiService.resultsBlocked){toast("本地记录读取失败，请先重试读取；原文件受保护");return;}
    boolean present=cachedArticles().stream().anyMatch(a->ContentDates.matches(a,dates))||aiService!=null&&(aiService.titles.dates().stream().anyMatch(dates)||aiService.results.keySet().stream().anyMatch(key->!ContentDates.date(key).isEmpty()&&dates.test(ContentDates.date(key))));if(!present){toast("所选日期没有缓存");return;}
    archive.deleteDates(dates);ContentDates.clearSnapshots(snapshots.values(),dates);ContentDates.clearSnapshots(linuxTabs.values(),dates);linuxFullCache.clear();
    if(aiService!=null)aiService.deleteDates(dates);archiveDirty=true;featuredKey=builtFeaturedKey="";historyAttempts.clear();historyResults.clear();saveArchive();saveNow();render(true);toast("已清理所选内容日期；收藏和个人设置保留");
  }
  private void aiSettings(){
    aiSettings(false);
  }
  private void aiSettings(boolean chat){
    if(aiService.loading||aiService.saving){toast("正在读取或保存 AI 配置，请稍候");return;}
    if(chat){if(aiService.enabled())AiChatDialog.show(ui,aiService,handler,palette);return;}
    AiSettingsDialog.show(ui,aiService.config,aiService.loadError,aiService.client,aiService.tests,handler,next->aiService.save(next,reason->{if(!destroyed)toast(reason.isEmpty()?(next.enabled?"已锁定模型："+next.model:"AI 已停用"):reason);}),()->{aiService.forget();toast("AI 配置已清除");},aiService,palette);
  }
  private String aiUsage(AiEngine.Result result){return result.requests<0?"历史结果，未记录调用统计":"请求 "+result.requests+" 次 / 复用 "+result.reused+" 批";}
  private void aiReport(String scope){
    if(aiService==null||!aiService.enabled())return;LinearLayout card=card();aiReportCards.put(scope,card);bindAiReport(scope,card);
  }
  private void updateAiReports(){for(Map.Entry<String,LinearLayout> entry:aiReportCards.entrySet())bindAiReport(entry.getKey(),entry.getValue());}
  private void bindAiReport(String scope,LinearLayout card){
    AiEngine.Result result=aiService.results.get(scope);String error=aiService.errors.get(scope),status=aiService.statuses.get(scope);
    String signature=(result==null?"":result.at+result.text+result.requests+":"+result.reused)+"|"+error+"|"+status+"|"+busy+"|"+aiService.running+"|"+aiService.saveError+"|"+aiService.savedState(scope);
    if(signature.equals(card.getTag()))return;card.setTag(signature);card.removeAllViews();card.addView(text("AI 更新汇报",15,INK,true));gap(card,8);
    if(status!=null)card.addView(text(status+"，可继续阅读上次汇报…",12,MUTED,false));
    if(error!=null)card.addView(text((status==null?"本次分析失败：":"上次分析失败：")+error+"；下方保留上次结果。",12,palette.danger,false));
    if(!aiService.saveError.isEmpty())card.addView(text(aiService.saveError,12,palette.danger,false));
    if(result==null)card.addView(text("只发送成功快照中的标题（含沿用缓存），不受页面筛选和分页影响。",13,MUTED,false));
    else{card.addView(text(result.text,14,INK,false));gap(card,8);card.addView(text(result.model+" · "+stamp(result.at,false)+" · "+result.count+" 条标题 · "+aiUsage(result)+" · "+aiService.savedState(scope)+"；AI 未核实正文",12,MUTED,false));}
    if(!busy&&!aiService.running)card.addView(filterButton(error==null?"更新本分区":"重试本分区",()->{aiService.run(engine,catalog,snapshots,sourceEnabled,archive.articles(),scope);render(true);}));
  }
  private ImageButton iconAction(String name,String description,Runnable action){ImageButton b=new ImageButton(ui);b.setImageDrawable(icon(name,ACCENT,19));b.setContentDescription(description);b.setBackground(ripple(shape(Color.TRANSPARENT,0,12)));b.setPadding(dp(10),dp(10),dp(10),dp(10));b.setOnClickListener(v->action.run());return b;}
  private String aiFloor(boolean ai){return aiFloors.getOrDefault(ai,java.time.LocalDate.parse(FeaturedNews.day(System.currentTimeMillis())).minusDays(2).toString());}
  private void aiFeatured(boolean ai){
    String scope=ai?AiEngine.FEATURED_AI:AiEngine.FEATURED_NEWS,today=FeaturedNews.day(System.currentTimeMillis()),floor=aiFloor(ai);
    body.addView(text("AI 精选 · "+aiService.config.model,13,INK,true));gap(body,10);if(!aiService.loadError.isEmpty())body.addView(text(aiService.loadError,12,palette.danger,false));
    List<String> days=new ArrayList<>();days.add(today);for(String key:aiService.results.keySet()){String day=ContentDates.date(key);if(ContentDates.scope(key).equals(scope)&&!day.isEmpty()&&day.compareTo(floor)>=0&&day.compareTo(today)<0&&!days.contains(day))days.add(day);}days.sort(Collections.reverseOrder());
    for(java.time.LocalDate d=java.time.LocalDate.parse(today).minusDays(3);d.toString().compareTo(floor)>=0;d=d.minusDays(1))if(!days.contains(d.toString()))days.add(d.toString());days.sort(Collections.reverseOrder());
    for(String day:days){String key=ContentDates.key(scope,day);AiEngine.Result result=aiService.daily(scope,day);boolean collapsed=collapsedAiDays.contains(key);
      LinearLayout heading=new LinearLayout(ui);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(text(day+(day.equals(today)?" · 今天":""),14,INK,true),new LinearLayout.LayoutParams(0,-2,1));
      ImageButton update=iconAction("refresh","更新 "+day+" "+(ai?"AI精选":"新闻精选"),()->requestAiDates(ai,Collections.singletonList(day),true));update.setEnabled(!busy&&!aiService.running);heading.addView(update,new LinearLayout.LayoutParams(dp(40),dp(40)));
      heading.addView(iconAction(collapsed?"chevron-down":"chevron-up",(collapsed?"展开 ":"收起 ")+day,()->{if(!collapsedAiDays.add(key))collapsedAiDays.remove(key);render(true);}),new LinearLayout.LayoutParams(dp(40),dp(40)));body.addView(heading);gap(body,8);
      if(collapsed)continue;String error=aiService.errors.get(key);if(error!=null)body.addView(text(error+"；旧精选保留",12,palette.danger,false));if(aiService.statuses.containsKey(key))body.addView(text(aiService.statuses.get(key)+"…",12,MUTED,false));
      if(result==null){body.addView(text(aiService.unavailable.contains(key)?"该日期暂无可获取内容；可点刷新图标重新检查":"尚无本地精选",13,MUTED,false));gap(body,10);continue;}
      body.addView(text(result.model+" · 整理于 "+stamp(result.at,false)+" · "+result.count+" 条标题 · "+aiUsage(result)+" · "+aiService.savedState(key),11,MUTED,false));gap(body,8);
      for(AiEngine.Group group:result.groups){LinearLayout c=card();c.addView(text(group.title,17,INK,true));gap(c,6);c.addView(text(group.reason,13,MUTED,false));gap(c,6);c.addView(text(group.reports.size()+" 篇原始报道",12,MUTED,false));c.setOnClickListener(v->openAiGroup(group));}
      if(result.groups.isEmpty()){body.addView(text(result.text,13,MUTED,false));gap(body,10);}
    }
    body.addView(text("显示至 "+floor+"；历史仅在点击后补充",11,MUTED,false));
    Button older=button("更早的本地 AI 精选",false,()->{List<String> olderDays=ContentDates.older(aiFloor(ai));aiFloors.put(ai,olderDays.get(2));requestAiDates(ai,olderDays,false);});older.setEnabled(!busy&&!aiService.running);body.addView(older);
  }
  private void requestAiDates(boolean ai,List<String> days,boolean force){
    if(busy||aiService.running||aiService.loading||aiService.saving||!aiService.enabled()){toast("请等待当前更新完成");return;}if(aiService.resultsBlocked){toast("AI 历史读取失败，请先重试读取");return;}
    List<Article> records=AiHistory.local(archive.articles(),snapshots,aiService.results);AiHistory.Plan plan=AiHistory.plan(ai,days,aiService.results,aiService.unavailable,records,engine,sourceEnabled,visibleAIBoards(),force);
    Runnable fetch=()->fetchAiDates(ai,new ArrayList<>(plan.fetch),force);render(true);
    if(!plan.local.isEmpty())aiService.selectDates(ai,plan.local,engine,sourceEnabled,snapshots,archive.articles(),false,fetch);else fetch.run();
  }
  private void fetchAiDates(boolean ai,List<String> days,boolean force){
    if(destroyed||days.isEmpty()||aiService==null||!aiService.enabled()||aiService.resultsBlocked)return;
    String day=days.remove(0),scope=ai?AiEngine.FEATURED_AI:AiEngine.FEATURED_NEWS;List<Source> sources=new ArrayList<>();
    for(Source s:engine.sources)if(Boolean.TRUE.equals(sourceEnabled.get(s.id))&&!s.id.equals("linuxdo")&&(ai?ReadingRules.platformSource(catalog,s)||NewsEngine.HOT_SOURCES.contains(s.id):FeaturedNews.latestSource(s))&&(force||AiHistory.canRecheck(snapshots.get(s.id),day)))sources.add(s);
    Runnable next=()->handler.post(()->fetchAiDates(ai,days,force));
    Runnable select=()->{List<Article> records=AiHistory.local(archive.articles(),snapshots,aiService.results);if(AiEngine.featuredInput(ai,records,engine,sourceEnabled,visibleAIBoards(),day,java.time.LocalDate.parse(day).plusDays(1).toString()).isEmpty()){if(!sources.isEmpty()&&failed>0)aiService.historyFailure(scope,Collections.singletonList(day));else aiService.noContent(scope,Collections.singletonList(day));next.run();}else aiService.selectDates(ai,Collections.singletonList(day),engine,sourceEnabled,snapshots,archive.articles(),force,next);render(true);};
    if(sources.isEmpty()){select.run();return;}
    afterDateFetch=()->{if(failed>0&&total>0&&failed==total){aiService.historyFailure(scope,Collections.singletonList(day));render(true);next.run();}else select.run();};startRefresh(sources,true,true,"",ai);
  }
  private void openAiGroup(AiEngine.Group group){
    LinearLayout content=vertical();content.setPadding(dp(20),dp(16),dp(20),dp(16));content.addView(text(group.reason+"\n仅依据标题的AI分析，请查看原始报道。",14,MUTED,false));
    for(Article article:group.reports){gap(content,12);content.addView(button(article.title,false,()->open(article)));content.addView(text(article.sourceName+" · "+publication(article),12,MUTED,false));}
    ScrollView view=new ScrollView(ui);view.addView(content);dialog().setTitle(group.title).setView(view).setPositiveButton("关闭",null).show();
  }
  private void themeOption(LinearLayout row,String mode,String label,String glyph){
    ReaderTheme preview=ReaderTheme.forMode(mode);boolean selected=mode.equals(savedState.themeMode);LinearLayout option=vertical();option.setPadding(dp(14),dp(14),dp(14),dp(14));option.setGravity(Gravity.CENTER_VERTICAL);option.setBackground(ripple(shape(preview.surface,selected?ACCENT:palette.line,16)));option.setSelected(selected);option.setFocusable(true);option.setContentDescription(label+"主题"+(selected?"，已选中":""));
    LinearLayout heading=new LinearLayout(ui);heading.setGravity(Gravity.CENTER_VERTICAL);ImageView image=new ImageView(ui);image.setImageDrawable(icon(glyph,preview.accent,22));heading.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));
    TextView name=text(label,15,preview.ink,true);name.setPadding(dp(9),0,0,0);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    if(selected){ImageView tick=new ImageView(ui);tick.setImageDrawable(icon("check",preview.accent,17));heading.addView(tick,new LinearLayout.LayoutParams(dp(17),dp(17)));}option.addView(heading);gap(option,16);
    FrameLayout miniature=new FrameLayout(ui);ReaderBackdrop picture=new ReaderBackdrop(ui);picture.showPage(0,preview);miniature.addView(picture,new FrameLayout.LayoutParams(-1,-1));LinearLayout sample=vertical();sample.setPadding(dp(7),dp(14),dp(7),0);for(int i=0;i<2;i++){View line=new View(ui);line.setBackground(shape(preview.dark?0xc21c3048:0xe8ffffff,preview.line,6));LinearLayout.LayoutParams lineLayout=new LinearLayout.LayoutParams(-1,dp(13));lineLayout.bottomMargin=dp(6);sample.addView(line,lineLayout);}miniature.addView(sample,new FrameLayout.LayoutParams(-1,-1));option.addView(miniature,new LinearLayout.LayoutParams(-1,dp(62)));
    option.setOnClickListener(v->{if(selected)return;int y=scroll.getScrollY();savedState.themeMode=mode;saveNow();applyTheme();chrome();render(false);scroll.post(()->scroll.scrollTo(0,y));});
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(row.getChildCount()==0?0:dp(10),0,0,0);row.addView(option,lp);
  }
  private interface Changed{void accept(boolean value);}
  private void styleSwitch(Switch control){
    control.setTextSize(large?17:15);control.setTextColor(INK);control.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));control.setPadding(0,dp(8),0,dp(8));control.setMinHeight(dp(48));control.setSwitchPadding(dp(18));
    control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,palette.muted}));control.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{palette.soft,palette.line}));
  }
  private void toggle(LinearLayout parent,String title,boolean checked,Changed action){Switch s=new Switch(ui);s.setText(title);styleSwitch(s);s.setChecked(checked);s.setOnCheckedChangeListener((v,value)->action.accept(value));parent.addView(s);}

  private int enabledCount(){int n=0;for(Boolean enabled:sourceEnabled.values())if(Boolean.TRUE.equals(enabled))n++;return n;}
  private void shareApk(){Uri uri=Uri.parse("content://"+getPackageName()+".apk/release.apk");Intent share=new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newUri(getContentResolver(),"一览独立版 APK",uri));try{startActivity(Intent.createChooser(share,"分享一览安装包"));}catch(ActivityNotFoundException e){toast("没有可接收安装包的分享应用，请使用电脑发送原始 APK");}}
  private void refresh(boolean force){
    if(busy||destroyed||engine==null||body==null||aiService!=null&&aiService.running)return;
    List<Source> selected=new ArrayList<>();long now=System.currentTimeMillis();
    for(Source s:engine.sources)if(!"linuxdo".equals(s.id)&&Boolean.TRUE.equals(sourceEnabled.get(s.id))&&(force||NewsEngine.shouldAutoCheck(s,snapshots.get(s.id),now)))selected.add(s);
    startRefresh(selected,force,false,"",false);
  }
  private void syncProgress(){
    if(progress==null)return;boolean aiRunning=aiService!=null&&aiService.running;
    progress.setIndeterminate(linuxUpdating);progress.setMax(aiRunning?Math.max(1,aiService.stageTotal):Math.max(total,1));progress.setProgress(aiRunning?aiService.stageDone:done);progress.setVisibility(busy||aiRunning?View.VISIBLE:View.INVISIBLE);
    progress.setContentDescription(aiRunning?aiService.stage+" · "+aiService.detail:"获取新闻 · "+done+"/"+total);
  }
  private void updateRefreshControls(){
    syncProgress();
    if(refresh!=null){boolean aiRunning=aiService!=null&&aiService.running,working=busy||aiRunning;
      if(search!=null&&working&&search.hasFocus())releaseSearch();
      refresh.setEnabled(!working&&(aiService==null||!aiService.saving));String label=aiRunning?"正在"+aiService.stage:busy?"正在获取新闻":"更新";if(!label.contentEquals(refresh.getText())){refresh.animate().cancel();refresh.setText(label);if(motionAllowed()&&foreground){refresh.setAlpha(.65f);refresh.animate().alpha(1).setDuration(160).start();}else refresh.setAlpha(1);}refresh.setSingleLine(true);
      if(updateTransition!=null)updateTransition.setWorking(working,motionAllowed()&&foreground);
      if(refreshSpin!=null){if(working&&foreground&&motionAllowed()){if(!refreshSpin.isStarted())refreshSpin.start();}else{refreshSpin.cancel();refreshGlyph.setLevel(0);}}
      refresh.setContentDescription(aiRunning?aiService.stage+" · "+aiService.detail:busy?"获取新闻 · "+done+"/"+total:"一键更新所有已启用来源");
    }
    if(linuxUpdateBtn!=null){linuxUpdateBtn.setText(linuxUpdating?"更新中…":"更新");linuxUpdateBtn.setEnabled(!busy&&(aiService==null||!aiService.running)&&Boolean.TRUE.equals(sourceEnabled.get("linuxdo")));}
  }
  private void refreshLinuxTab(){
    if(busy||destroyed||engine==null||body==null||aiService!=null&&aiService.running)return;
    final Source source=engine.sourceMap.get("linuxdo");
    if(source==null||!Boolean.TRUE.equals(sourceEnabled.get(source.id)))return;
    final int mode=linuxTab;final String key=linuxTabKey(mode);final Snapshot old=linuxTabs.get(key);
    // Explicit one-shot refresh: bypass the ordinary cooldown; result is kept in the sub-page map, never the archive.
    busy=true;linuxUpdating=true;done=0;total=1;updateRefreshControls();
    network.execute(()->{
      final Snapshot value=engine.fetchLinuxDo(source,old,mode);
      handler.post(()->{
        if(destroyed)return;linuxTabs.put(key,value);
        done=1;busy=false;linuxUpdating=false;updateRefreshControls();
        toast(value.error.isEmpty()?"LINUX DO「"+key+"」已获取 "+value.items.size()+" 条":"LINUX DO 更新失败，请查看来源提示");
        render(true);saveSoon();
      });
    });
  }
  private void startRefresh(List<Source> selected,boolean force,boolean recheck,String historyKey,boolean ai){
    if(busy||destroyed||aiService!=null&&aiService.running)return;
    if(selected.isEmpty()){if(afterDateFetch!=null){Runnable action=afterDateFetch;afterDateFetch=null;action.run();}if(!historyKey.isEmpty()){historyResults.put(historyKey,"没有已启用的相关来源");render(true);}return;}
    busy=true;done=updated=failed=cached=0;total=selected.size();updateRefreshControls();progress.setMax(total);progress.setProgress(0);progress.setVisibility(View.VISIBLE);
    for(Source s:selected){final Snapshot old=snapshots.get(s.id);network.execute(()->{
      Snapshot value=recheck?engine.recheck(s,old):engine.fetch(s,old,force);handler.post(()->{
        if(destroyed)return;snapshots.put(s.id,value);if(!value.cached&&value.error.isEmpty()&&!"linuxdo".equals(s.id)){long before=archive.revision;archive.accept(value,engine,System.currentTimeMillis());if(archive.revision!=before){archiveDirty=true;handler.removeCallbacks(archiveSaveTask);handler.postDelayed(archiveSaveTask,500);}}done++;if(!value.error.isEmpty())failed++;else if(value.cached)cached++;else updated++;
        progress.setProgress(done);saveSoon();
        if(done==total){busy=false;updateRefreshControls();toast("更新 "+updated+" · 沿用 "+cached+" · 失败 "+failed);
          if(!historyKey.isEmpty()){String day=historyKey.substring(historyKey.indexOf(':')+1);boolean returned=false;for(Source src:selected){Snapshot snap=snapshots.get(src.id);if(snap!=null&&snap.error.isEmpty())for(Article a:snap.items)if(a.published>0&&FeaturedNews.day(a.published).equals(day))returned=true;}historyResults.put(historyKey,returned?"来源已返回该日条目，未形成该日独立精选":failed==total?"本次检查失败，旧记录已保留":"来源没有返回这一天（"+day+"）");}
          if(afterDateFetch!=null){Runnable action=afterDateFetch;afterDateFetch=null;action.run();}
          else if(!recheck&&historyKey.isEmpty()&&aiService!=null&&aiService.enabled()){render(true);aiService.run(engine,catalog,snapshots,sourceEnabled,archive.articles());}
          else render(true);if(!pendingDay.isEmpty()){String day=pendingDay;boolean scope=pendingAI;pendingDay="";handler.post(()->requestHistory(scope,day));}}
      });
    });}
  }
  private String publication(Article a){return a.published>0?stamp(a.published,a.dateOnly):"未提供";}
  private void loadArchive(){try{if(archiveStorage.getBaseFile().exists()||new File(archiveStorage.getBaseFile()+".bak").exists()){byte[] bytes;try(InputStream in=archiveStorage.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>NewsArchive.MAX_BYTES)throw new IOException("归档过大");out.write(buffer,0,n);}bytes=out.toByteArray();}JSONObject j=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(j.optInt("schema")!=1||j.optJSONArray("records")==null)throw new IOException("归档结构无效");archive.restoreChecked(j,engine);}archiveWritable=true;archiveLoadError="";}catch(Exception e){archiveWritable=false;archiveLoadError="新闻归档读取失败，原文件保留，已暂停覆盖保存；仍可阅读其他本地缓存。";}}
  private void saveArchive(){
    if(!archiveDirty||archiveStorage==null||disk.isShutdown()||!archiveWritable)return;
    try{final byte[] bytes=archive.json().toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>NewsArchive.MAX_BYTES)throw new IOException("归档过大");archiveDirty=false;
      disk.execute(()->{FileOutputStream out=null;try{out=archiveStorage.startWrite();out.write(bytes);archiveStorage.finishWrite(out);handler.post(()->{archiveSaveError="";if(!destroyed&&!busy&&!aiService.running)render(true);});}catch(Exception e){if(out!=null)archiveStorage.failWrite(out);handler.post(()->{archiveDirty=true;archiveSaveError="新闻归档未保存，请检查剩余空间；旧文件保留。";if(!destroyed){toast(archiveSaveError);render(true);}});}});
    }catch(Exception e){archiveSaveError="新闻归档未保存，旧文件保留。";if(!destroyed)toast(archiveSaveError);}
  }
  private String stamp(long value,boolean dateOnly){SimpleDateFormat f=new SimpleDateFormat(dateOnly?"yyyy-MM-dd":"MM-dd HH:mm",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(value));}
  private void load(){try{if(!storage.getBaseFile().exists()||storage.getBaseFile().length()>8*1024*1024)return;JSONObject j=new JSONObject(new String(storage.readFully(),StandardCharsets.UTF_8));savedState.restore(engine,j);compact=savedState.compact;large=savedState.large;unread=savedState.unread;hotMode=savedState.hotMode;}catch(Exception ignored){}}
  private void saveSoon(){handler.removeCallbacks(saveTask);handler.postDelayed(saveTask,350);}
  private void saveNow(){if(storage==null||disk.isShutdown())return;try{savedState.compact=compact;savedState.large=large;savedState.unread=unread;savedState.hotMode=hotMode;byte[] bytes=savedState.json().toString().getBytes(StandardCharsets.UTF_8);disk.execute(()->{FileOutputStream out=null;try{out=storage.startWrite();out.write(bytes);storage.finishWrite(out);}catch(Exception e){if(out!=null)storage.failWrite(out);if(!destroyed)handler.post(()->toast("缓存保存失败，请检查设备剩余空间"));}});}catch(Exception ignored){}}
  private void releaseSearch(){if(search==null)return;boolean active=search.hasFocus();search.clearFocus();search.setCursorVisible(false);search.setFocusable(false);if(active){handler.removeCallbacks(titleRequest);handler.postDelayed(titleRequest,200);}if(active)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
  @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&search!=null&&search.hasFocus()){android.graphics.Rect bounds=new android.graphics.Rect();search.getGlobalVisibleRect(bounds);if(!bounds.contains((int)event.getRawX(),(int)event.getRawY()))releaseSearch();}return super.dispatchTouchEvent(event);}
  @Override protected void onPause(){releaseSearch();foreground=false;if(refreshSpin!=null)refreshSpin.cancel();if(updateTransition!=null)updateTransition.finish();if(refresh!=null){refresh.animate().cancel();refresh.setAlpha(1);}if(body!=null){body.animate().cancel();body.setAlpha(1);body.setTranslationY(0);}saveNow();handler.removeCallbacks(archiveSaveTask);saveArchive();super.onPause();}
  @Override protected void onResume(){super.onResume();foreground=true;updateRefreshControls();}
  @Override protected void onDestroy(){destroyed=true;handler.removeCallbacks(titleRequest);if(refreshSpin!=null)refreshSpin.cancel();if(updateTransition!=null)updateTransition.finish();handler.removeCallbacks(archiveSaveTask);saveArchive();handler.removeCallbacks(saveTask);saveNow();network.shutdownNow();if(aiService!=null)aiService.close();linuxFullCache.clear();disk.shutdown();super.onDestroy();}
}
