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
    messages=new LinearLayout(context);messages.setTag("ai-chat-messages");messages.setOrientation(1);messages.setPadding(0,dp(12),0,dp(8));scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.addView(messages);parent.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    input=new EditText(context);input.setTag("ai-chat-input");input.setHint("输入消息");input.setTextColor(theme.ink);input.setHintTextColor(theme.muted);input.setTextSize(15);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);input.setGravity(Gravity.TOP|Gravity.START);input.setMinLines(1);input.setMaxLines(4);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);input.setBackground(box(theme.raised));input.setPadding(dp(14),dp(12),dp(14),dp(12));input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){send();return true;}return false;});LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(-1,-2);field.topMargin=dp(8);parent.addView(input,field);
    LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.CENTER_VERTICAL);actions.setPadding(0,dp(12),0,0);Button clear=action("清空对话",false);clear.setTag("ai-chat-clear");clear.setOnClickListener(v->{ticket++;busy=false;history.clear();messages.removeAllViews();input.setText("");ready();});actions.addView(clear,new LinearLayout.LayoutParams(-2,dp(48)));send=action("发送",true);send.setTag("ai-chat-send");send.setOnClickListener(v->send());LinearLayout.LayoutParams sendLayout=new LinearLayout.LayoutParams(0,dp(48),1);sendLayout.leftMargin=dp(20);actions.addView(send,sendLayout);parent.addView(actions);
  }
  private Button action(String label,boolean primary){Button b=new Button(context);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(16),0,dp(16),0);b.setStateListAnimator(null);b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{theme.muted,primary?theme.onAccent:theme.muted}));b.setBackgroundTintList(null);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(theme.dark?0x227fffff:0x18155e85),box(primary?theme.accent:theme.surface),null));return b;}
  public void compact(boolean keyboard){input.setMaxLines(keyboard?2:4);}
  private void ready(){send.setText("发送");send.setEnabled(true);}
  private void bubble(String text,boolean user){LinearLayout row=new LinearLayout(context);row.setGravity(user?Gravity.END:Gravity.START);TextView value=new TextView(context);value.setText(text);value.setTag(user?"chat-user":"chat-assistant");value.setTextSize(15);value.setTextColor(user?theme.onAccent:theme.ink);value.setPadding(dp(14),dp(11),dp(14),dp(11));value.setTextIsSelectable(true);value.setMaxWidth(Math.round(context.getResources().getDisplayMetrics().widthPixels*.82f));value.setBackground(box(user?theme.accent:theme.raised));row.addView(value,new LinearLayout.LayoutParams(-2,-2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);messages.addView(row,lp);scroll.post(()->scroll.scrollTo(0,messages.getHeight()));}
  private void send(){
    if(busy||closed)return;if(!session.enabled()){bubble("请先保存并启用模型",false);return;}String text=input.getText().toString().trim();if(text.isEmpty())return;if(text.length()>4000){bubble("消息过长，请分段发送",false);return;}
    final AiClient.Config locked=session.config;final long request=++ticket;
    try{while(history.size()>22)history.subList(0,2).clear();JSONArray turns=new JSONArray().put(new JSONObject().put("role","system").put("content","请使用中文回答。你没有设备或抓取工具，不得声称执行了实际未执行的操作。"));for(JSONObject turn:history)turns.put(turn);JSONObject user=new JSONObject().put("role","user").put("content",text);turns.put(user);bubble(text,true);input.setText("");busy=true;send.setText("正在回复…");send.setEnabled(false);
      session.tests.execute(()->{try{String reply=session.client.chat(locked,turns,1800);handler.post(()->{if(closed||request!=ticket)return;busy=false;ready();if(!session.enabled()||!session.config.id.equals(locked.id)){bubble("配置已改变，未采用旧模型回复",false);return;}try{history.add(user);history.add(new JSONObject().put("role","assistant").put("content",reply));}catch(Exception ignored){}bubble(reply,false);});}catch(Exception e){String error=AiClient.error(e);handler.post(()->{if(closed||request!=ticket)return;busy=false;ready();bubble(error,false);});}});
    }catch(Exception e){bubble(AiClient.error(e),false);}
  }
  public void close(){closed=true;ticket++;history.clear();messages.removeAllViews();input.setText("");}
}
