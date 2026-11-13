package cn.yilan.news;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Explicit draft/test/select/save flow. A discovery response never selects a model. */
public final class AiSettingsDialog {
  private static TextView label(Context c,String text){TextView t=new TextView(c);t.setText(text);t.setTextSize(14);t.setPadding(0,12,0,8);return t;}
  private static EditText edit(Context c,String hint,boolean secret){EditText e=new EditText(c);e.setHint(hint);e.setSingleLine();e.setInputType(secret?129:1);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);if(secret)e.setLongClickable(false);return e;}
  public static AlertDialog show(Context c,AiClient.Config existing,String loadError,AiClient client,ExecutorService worker,Handler handler,Consumer<AiClient.Config> save,Runnable forget){
    return show(c,existing,loadError,client,worker,handler,save,forget,null,ReaderTheme.forMode("day"));
  }
  public static AlertDialog show(Context c,AiClient.Config existing,String loadError,AiClient client,ExecutorService worker,Handler handler,Consumer<AiClient.Config> save,Runnable forget,AiCoordinator session,ReaderTheme theme){
    LinearLayout content=new LinearLayout(c);content.setOrientation(1);int p=Math.round(20*c.getResources().getDisplayMetrics().density);content.setPadding(p,p,p,p);
    if(!loadError.isEmpty())content.addView(label(c,loadError));
    EditText url=edit(c,"https://你的服务地址/v1",false),key=edit(c,"API Key",true),model=edit(c,"选择或填写模型 ID",false);url.setTag("ai-url");key.setTag("ai-key");model.setTag("ai-model");
    content.addView(label(c,"URL"));content.addView(url);content.addView(label(c,"API Key"));content.addView(key);
    Button discover=new Button(c);discover.setAllCaps(false);discover.setText("测试连接并获取模型列表");content.addView(discover);
    TextView status=label(c,"");status.setVisibility(View.GONE);status.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int n,int b){}public void onTextChanged(CharSequence s,int a,int b,int n){status.setVisibility(s.length()==0?View.GONE:View.VISIBLE);}public void afterTextChanged(Editable e){}});content.addView(status);
    RadioGroup choices=new RadioGroup(c);ScrollView modelList=new ScrollView(c);modelList.addView(choices);content.addView(modelList,new LinearLayout.LayoutParams(-1,Math.round(140*c.getResources().getDisplayMetrics().density)));modelList.setVisibility(View.GONE);content.addView(label(c,"锁定模型 ID"));content.addView(model);
    Button probe=new Button(c);probe.setAllCaps(false);probe.setText("测试所选模型（极短请求）");content.addView(probe);
    if(existing!=null){url.setText(existing.url);key.setText(existing.key);model.setText(existing.model);}
    ScrollView scroll=new ScrollView(c);scroll.addView(content);
    AlertDialog dialog=new AlertDialog.Builder(c).setView(scroll).setPositiveButton("保存并启用",null).setNegativeButton("取消",null).setNeutralButton(existing!=null&&existing.enabled?"停用 AI":"清除配置",null).create();
    final String[] verified={null};final boolean[] testing={false};
    Runnable invalidate=()->{verified[0]=null;if(dialog.isShowing())dialog.getButton(-1).setEnabled(false);};
    TextWatcher changed=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int n,int b){}public void onTextChanged(CharSequence s,int a,int b,int n){invalidate.run();}public void afterTextChanged(Editable e){}};
    url.addTextChangedListener(changed);key.addTextChangedListener(changed);model.addTextChangedListener(changed);
    dialog.setOnShowListener(v->{dialog.getButton(-1).setEnabled(false);
      dialog.getButton(-1).setOnClickListener(w->{try{
        AiClient.Config draft=new AiClient.Config(url.getText().toString(),key.getText().toString(),model.getText().toString(),UUID.randomUUID().toString(),true);
        if(existing!=null&&draft.url.equals(existing.url)&&draft.key.equals(existing.key)&&draft.model.equals(existing.model))draft=new AiClient.Config(draft.url,draft.key,draft.model,existing.id,true);
        String marker=draft.url+"\n"+draft.key+"\n"+draft.model;if(!marker.equals(verified[0])){status.setText("配置改变，请重新测试所选模型");return;}
        save.accept(draft);dialog.dismiss();
      }catch(Exception e){status.setText(AiClient.error(e));}});
      dialog.getButton(-3).setOnClickListener(w->{
        if(existing!=null&&existing.enabled){try{save.accept(new AiClient.Config(existing.url,existing.key,existing.model,existing.id,false));dialog.dismiss();}catch(Exception e){status.setText(AiClient.error(e));}}
        else new AlertDialog.Builder(c).setMessage("清除本机 AI 密钥及 AI 缓存？收藏和新闻不受影响。").setNegativeButton("取消",null).setPositiveButton("清除",(d,x)->{forget.run();dialog.dismiss();}).show();
      });
    });
    discover.setOnClickListener(v->{if(testing[0])return;try{
      final AiClient.Config draft=new AiClient.Config(url.getText().toString(),key.getText().toString(),"","draft",true);testing[0]=true;discover.setEnabled(false);probe.setEnabled(false);status.setText("正在连接并读取模型列表…");
      worker.execute(()->{try{AiClient.Models found=client.models(draft);handler.post(()->{
        testing[0]=false;if(!dialog.isShowing())return;discover.setEnabled(true);probe.setEnabled(true);
        try{if(!AiClient.baseURL(url.getText().toString()).equals(draft.url)||!key.getText().toString().trim().equals(draft.key)){status.setText("输入已改变，请重新测试");return;}}catch(Exception e){status.setText("输入已改变，请重新测试");return;}
        modelList.setVisibility(View.VISIBLE);choices.removeAllViews();for(String id:found.ids){RadioButton option=new RadioButton(c);option.setText(id+" · 待验证");option.setOnClickListener(w->{model.setText(id);invalidate.run();});choices.addView(option);}
        style(c,choices,theme);status.setText(found.fromServer?"已获取 "+found.ids.size()+" 个模型，请选择后测试。":"请手动填写模型 ID 并测试。");
      });}catch(Exception e){String reason=AiClient.error(e);handler.post(()->{testing[0]=false;if(dialog.isShowing()){discover.setEnabled(true);probe.setEnabled(true);status.setText(reason+"；如服务没有列表，可填写模型 ID 测试。");}});}});
    }catch(Exception e){status.setText(AiClient.error(e));}});
    probe.setOnClickListener(v->{if(testing[0])return;try{
      final AiClient.Config draft=new AiClient.Config(url.getText().toString(),key.getText().toString(),model.getText().toString(),"draft",true);if(draft.model.isEmpty())throw new AiClient.Failure("请选择或填写模型 ID");
      testing[0]=true;discover.setEnabled(false);probe.setEnabled(false);invalidate.run();status.setText("正在验证所选模型，未选择其他模型…");
      worker.execute(()->{try{client.probe(draft);handler.post(()->{
        testing[0]=false;if(!dialog.isShowing())return;discover.setEnabled(true);probe.setEnabled(true);
        try{AiClient.Config current=new AiClient.Config(url.getText().toString(),key.getText().toString(),model.getText().toString(),"draft",true);
          if(!current.url.equals(draft.url)||!current.key.equals(draft.key)||!current.model.equals(draft.model)){status.setText("输入已改变，请重新测试");return;}
          verified[0]=draft.url+"\n"+draft.key+"\n"+draft.model;dialog.getButton(-1).setEnabled(true);status.setText("验证成功："+draft.model+"。保存后固定使用该模型，失败不会切换。");
        }catch(Exception e){status.setText(AiClient.error(e));}
      });}catch(Exception e){String reason=AiClient.error(e);handler.post(()->{testing[0]=false;if(dialog.isShowing()){discover.setEnabled(true);probe.setEnabled(true);status.setText(reason);}});}});
    }catch(Exception e){status.setText(AiClient.error(e));}});
    dialog.show();dialog.getButton(-1).setEnabled(false);style(c,content,theme);if(dialog.getWindow()!=null){android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(theme.surface);background.setCornerRadius(p);dialog.getWindow().setBackgroundDrawable(background);dialog.getWindow().setLayout(Math.min(c.getResources().getDisplayMetrics().widthPixels-p,Math.round(680*c.getResources().getDisplayMetrics().density)),-2);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}return dialog;
  }
  private static void style(Context c,View view,ReaderTheme theme){
    int pad=Math.round(12*c.getResources().getDisplayMetrics().density);
    if(view instanceof TextView){TextView t=(TextView)view;t.setTextColor(theme.ink);t.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));}
    if(view instanceof EditText||view instanceof Button&&!(view instanceof RadioButton)){android.graphics.drawable.GradientDrawable box=new android.graphics.drawable.GradientDrawable();box.setColor(view instanceof Button?theme.soft:theme.raised);box.setCornerRadius(pad);box.setStroke(Math.max(1,pad/12),theme.line);view.setBackground(box);view.setPadding(pad,pad,pad,pad);view.setMinimumHeight(pad*4);if(view instanceof TextView)((TextView)view).setTextSize(14);if(view instanceof Button&&view.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)view.getLayoutParams();lp.topMargin=pad;view.setLayoutParams(lp);}}
    if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)style(c,group.getChildAt(i),theme);}
  }

}
