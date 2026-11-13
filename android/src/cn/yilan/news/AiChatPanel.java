package cn.yilan.news;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Explicit manual chat, pinned to the saved model. Conversation stays in this window only. */
public final class AiChatPanel {
  private final Context context;private final ReaderTheme theme;private final AiCoordinator session;private final Handler handler;
  private final List<JSONObject> history=new ArrayList<>();private final LinearLayout messages;private final ScrollView scroll;private final EditText input;private final Button send;
  private boolean closed,busy;private long ticket;
  private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
  private GradientDrawable box(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));d.setStroke(dp(1),theme.line);return d;}
  public AiChatPanel(Context context,LinearLayout parent,AiCoordinator session,Handler handler,ReaderTheme theme){
    this.context=context;this.session=session;this.handler=handler;this.theme=theme;
    TextView title=new TextView(context);title.setText("与模型对话");title.setTypeface(null,Typeface.BOLD);title.setTextColor(theme.ink);title.setTextSize(16);title.setPadding(0,dp(20),0,dp(10));parent.addView(title);
    messages=new LinearLayout(context);messages.setOrientation(1);messages.setPadding(dp(12),dp(8),dp(12),dp(8));scroll=new ScrollView(context);scroll.setBackground(box(theme.soft));scroll.addView(messages);parent.addView(scroll,new LinearLayout.LayoutParams(-1,dp(180)));
    bubble("本次窗口对话 · "+session.config.model,false);
    input=new EditText(context);input.setTag("ai-chat-input");input.setHint("输入消息");input.setTextColor(theme.ink);input.setTextSize(14);input.setMinLines(2);input.setMaxLines(5);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);input.setBackground(box(theme.surface));input.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(-1,-2);field.topMargin=dp(10);parent.addView(input,field);
    LinearLayout actions=new LinearLayout(context);send=new Button(context);send.setText("发送");send.setAllCaps(false);send.setOnClickListener(v->send());actions.addView(send,new LinearLayout.LayoutParams(0,dp(48),1));Button clear=new Button(context);clear.setText("清空对话");clear.setAllCaps(false);clear.setOnClickListener(v->{ticket++;busy=false;history.clear();messages.removeAllViews();send.setText("发送");send.setEnabled(true);input.setEnabled(true);});actions.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));parent.addView(actions);
  }
  private void bubble(String text,boolean user){TextView value=new TextView(context);value.setText((user?"你\n":"")+text);value.setTextSize(14);value.setTextColor(theme.ink);value.setPadding(dp(10),dp(9),dp(10),dp(9));value.setTextIsSelectable(true);value.setBackground(box(user?theme.surface:theme.soft));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);messages.addView(value,lp);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
  private void send(){
    if(busy||closed)return;if(!session.enabled()){bubble("请先保存并启用模型",false);return;}String text=input.getText().toString().trim();if(text.isEmpty())return;if(text.length()>4000){bubble("消息过长，请分段发送",false);return;}
    final AiClient.Config locked=session.config;final long request=++ticket;
    try{while(history.size()>22)history.subList(0,2).clear();JSONArray turns=new JSONArray().put(new JSONObject().put("role","system").put("content","请使用中文回答。你没有设备或抓取工具，不得声称执行了实际未执行的操作。"));for(JSONObject turn:history)turns.put(turn);JSONObject user=new JSONObject().put("role","user").put("content",text);turns.put(user);bubble(text,true);input.setText("");busy=true;send.setText("正在回复…");send.setEnabled(false);input.setEnabled(false);
      session.tests.execute(()->{try{String reply=session.client.chat(locked,turns,1800);handler.post(()->{if(closed||request!=ticket)return;busy=false;send.setText("发送");send.setEnabled(true);input.setEnabled(true);if(!session.enabled()||!session.config.id.equals(locked.id)){bubble("配置已改变，未采用旧模型回复",false);return;}try{history.add(user);history.add(new JSONObject().put("role","assistant").put("content",reply));}catch(Exception ignored){}bubble(reply,false);});}catch(Exception e){String error=AiClient.error(e);handler.post(()->{if(closed||request!=ticket)return;busy=false;send.setText("发送");send.setEnabled(true);input.setEnabled(true);bubble(error,false);});}});
    }catch(Exception e){bubble(AiClient.error(e),false);}
  }
  public void close(){closed=true;ticket++;history.clear();}
}
