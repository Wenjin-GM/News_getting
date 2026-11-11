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
    LinearLayout content=new LinearLayout(c);content.setOrientation(1);int p=Math.round(20*c.getResources().getDisplayMetrics().density);content.setPadding(p,p,p,p);
    content.addView(label(c,"GLM / DeepSeek 标准 API 或其他 OpenAI 兼容接口。启用后，更新会自动发送各分区标题用于汇报和精选；翻译仅在点击时发送预览文本。可能产生服务商费用。"));
    if(!loadError.isEmpty())content.addView(label(c,loadError));
    EditText url=edit(c,"HTTPS API 基础地址",false),key=edit(c,"API Key（仅本机加密保存）",true),model=edit(c,"选择候选或填写模型 ID",false);
    content.addView(label(c,"URL"));content.addView(url);content.addView(label(c,"API Key"));content.addView(key);
    LinearLayout presets=new LinearLayout(c);for(String provider:new String[]{"GLM","DeepSeek"}){Button b=new Button(c);b.setAllCaps(false);b.setText(provider);b.setOnClickListener(v->url.setText(provider.equals("GLM")?"https://open.bigmodel.cn/api/paas/v4":"https://api.deepseek.com"));presets.addView(b,new LinearLayout.LayoutParams(0,-2,1));}content.addView(presets);
    Button discover=new Button(c);discover.setAllCaps(false);discover.setText("测试连接并获取模型列表");content.addView(discover);
    TextView status=label(c,"模型列表中的 ID 仍需验证聊天权限；只有所选模型测试成功后才能保存。服务不提供列表时可填写 ID 验证。");content.addView(status);
    RadioGroup choices=new RadioGroup(c);content.addView(choices);content.addView(label(c,"锁定模型 ID"));content.addView(model);
    Button probe=new Button(c);probe.setAllCaps(false);probe.setText("测试所选模型（极短请求）");content.addView(probe);
    if(existing!=null){url.setText(existing.url);key.setText(existing.key);model.setText(existing.model);content.addView(label(c,"当前保存模型："+existing.model+(existing.enabled?" · 已启用":" · 已停用")));}
    ScrollView scroll=new ScrollView(c);scroll.addView(content);
    AlertDialog dialog=new AlertDialog.Builder(c).setTitle("AI 功能").setView(scroll).setPositiveButton("保存并启用",null).setNegativeButton("取消",null).setNeutralButton(existing!=null&&existing.enabled?"停用 AI":"清除配置",null).create();
    final String[] verified={null};final boolean[] testing={false};
    Runnable invalidate=()->{verified[0]=null;if(dialog.isShowing())dialog.getButton(-1).setEnabled(false);};
    TextWatcher changed=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int n,int b){}public void onTextChanged(CharSequence s,int a,int b,int n){invalidate.run();}public void afterTextChanged(Editable e){}};
    url.addTextChangedListener(changed);key.addTextChangedListener(changed);model.addTextChangedListener(changed);
    dialog.setOnShowListener(v->{dialog.getButton(-1).setEnabled(false);
      dialog.getButton(-1).setOnClickListener(w->{try{
        AiClient.Config draft=new AiClient.Config(url.getText().toString(),key.getText().toString(),model.getText().toString(),UUID.randomUUID().toString(),true);
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
        choices.removeAllViews();for(String id:found.ids){RadioButton option=new RadioButton(c);option.setText(id+" · 待验证");option.setOnClickListener(w->{model.setText(id);invalidate.run();});choices.addView(option);}
        status.setText(found.fromServer?"模型列表返回 "+found.ids.size()+" 个候选（最多500个），请选择并测试聊天权限。":"列表读取未成功："+found.detail+"。以下仅为官方目录候选，尚未验证这把 Key 的权限；也可填写其他模型 ID 测试。");
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
    dialog.show();dialog.getButton(-1).setEnabled(false);return dialog;
  }
}
