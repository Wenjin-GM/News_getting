package cn.yilan.news;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Native publication-date calendar, with explicit user confirmation for cache deletion. */
public final class CacheCalendarDialog {
  private final Context context;private final ReaderTheme theme;private final Map<String,Integer> dates;private final Set<String> selected=new LinkedHashSet<>();
  private final Consumer<Set<String>> clear;private final Consumer<String> before;private final LinearLayout body;private final AlertDialog dialog;
  private YearMonth month=YearMonth.from(LocalDate.parse(FeaturedNews.day(System.currentTimeMillis())));
  private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
  private TextView text(String value,int size){TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(theme.ink);return t;}
  private Button button(String title,Runnable action){Button b=new Button(context);b.setText(title);b.setAllCaps(false);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),dp(4),dp(4),dp(4));b.setOnClickListener(v->action.run());return b;}
  public static AlertDialog show(Context context,ReaderTheme theme,Map<String,Integer> dates,Consumer<Set<String>> clear,Consumer<String> before){return new CacheCalendarDialog(context,theme,dates,clear,before).dialog;}
  private CacheCalendarDialog(Context context,ReaderTheme theme,Map<String,Integer> dates,Consumer<Set<String>> clear,Consumer<String> before){
    this.context=context;this.theme=theme;this.dates=dates;this.clear=clear;this.before=before;body=new LinearLayout(context);body.setOrientation(1);body.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(context);scroll.addView(body);dialog=new AlertDialog.Builder(context).setTitle("按内容日期清理缓存").setView(scroll).setNegativeButton("关闭",null).create();draw();dialog.show();
  }
  private ImageButton arrow(String name,String description,Runnable action){ImageButton b=new ImageButton(context);b.setImageDrawable(new ReaderIcon(name,theme.accent,dp(20)));b.setBackgroundColor(android.graphics.Color.TRANSPARENT);b.setContentDescription(description);b.setOnClickListener(v->action.run());return b;}
  private void draw(){
    body.removeAllViews();LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.addView(arrow("previous","上个月",()->{if(month.getYear()>1900){month=month.minusMonths(1);draw();}}),new LinearLayout.LayoutParams(dp(44),dp(44)));TextView caption=text(month.toString(),18);caption.setGravity(Gravity.CENTER);header.addView(caption,new LinearLayout.LayoutParams(0,-2,1));header.addView(arrow("next","下个月",()->{if(month.getYear()<2200){month=month.plusMonths(1);draw();}}),new LinearLayout.LayoutParams(dp(44),dp(44)));body.addView(header);
    body.addView(text("带 • 的日期有缓存；收藏与个人设置保留",12));GridLayout grid=new GridLayout(context);grid.setColumnCount(7);body.addView(grid,new LinearLayout.LayoutParams(-1,-2));
    for(String name:new String[]{"一","二","三","四","五","六","日"}){TextView t=text(name,12);t.setGravity(Gravity.CENTER);cell(grid,t,28);}
    int offset=month.atDay(1).getDayOfWeek().getValue()-1;for(int i=0;i<offset;i++)cell(grid,new Space(context),42);
    for(int day=1;day<=month.lengthOfMonth();day++){String date=month.atDay(day).toString();boolean has=dates.containsKey(date),chosen=selected.contains(date);Button b=button(day+(has?" •":""),()->{if(!selected.add(date))selected.remove(date);draw();});b.setContentDescription(date+(has?"，有缓存":"，无缓存")+(chosen?"，已选":""));b.setTextColor(chosen?theme.onAccent:has?theme.accent:theme.muted);b.setTypeface(null,has?Typeface.BOLD:Typeface.NORMAL);GradientDrawable fill=new GradientDrawable();fill.setColor(chosen?theme.accent:android.graphics.Color.TRANSPARENT);fill.setCornerRadius(dp(10));b.setBackground(fill);cell(grid,b,42);}
    TextView summary=text("已选 "+selected.size()+" 天",13);summary.setPadding(0,dp(10),0,dp(6));body.addView(summary);
    Button exact=button("清除选中日期",()->confirm(false));exact.setEnabled(!selected.isEmpty());body.addView(exact,new LinearLayout.LayoutParams(-1,dp(46)));Button older=button("清除此日期之前",()->confirm(true));older.setEnabled(selected.size()==1);body.addView(older,new LinearLayout.LayoutParams(-1,dp(46)));
  }
  private void cell(GridLayout grid,View view,int height){GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(height);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);grid.addView(view,p);}
  private void confirm(boolean older){if(selected.isEmpty()||older&&selected.size()!=1)return;Set<String> chosen=new LinkedHashSet<>(selected);String cutoff=chosen.iterator().next();new AlertDialog.Builder(context).setTitle("确认清理").setMessage(older?"清除内容日期早于 "+cutoff+" 的缓存（不含当天）。收藏和个人设置保留。":"清除所选 "+chosen.size()+" 天的新闻、精选和关联翻译缓存。收藏和个人设置保留。").setNegativeButton("取消",null).setPositiveButton("清理",(d,w)->{if(older)before.accept(cutoff);else clear.accept(chosen);dialog.dismiss();}).show();}
}
