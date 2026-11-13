package cn.yilan.news.test;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import cn.yilan.news.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Actual soft-keyboard + touch regression, synthetic model only, disposable AVD. */
public final class ChatKeyboardSmoke extends Instrumentation {
  private Activity activity;private Dialog dialog;private AiCoordinator session;private String step="start";private final AtomicInteger calls=new AtomicInteger();private boolean hold;
  @Override public void onCreate(Bundle args){super.onCreate(args);start();}
  private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
  private void main(Runnable action){Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new RuntimeException(failure[0]);}
  private void waitUntil(java.util.function.BooleanSupplier condition)throws Exception{long end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){boolean[] ready={false};main(()->ready[0]=condition.getAsBoolean());if(ready[0])return;SystemClock.sleep(25);}throw new AssertionError("Timeout: "+step);}
  private View tagged(String tag){return dialog.getWindow().getDecorView().findViewWithTag(tag);}
  private Context themed(String mode){try{java.lang.reflect.Field state=activity.getClass().getDeclaredField("savedState"),ui=activity.getClass().getDeclaredField("ui");state.setAccessible(true);ui.setAccessible(true);((NewsState)state.get(activity)).themeMode=mode;for(String name:new String[]{"applyTheme","chrome"}){java.lang.reflect.Method m=activity.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}java.lang.reflect.Method render=activity.getClass().getDeclaredMethod("render",boolean.class);render.setAccessible(true);render.invoke(activity,false);return (Context)ui.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
  private int ime(){WindowInsets insets=dialog.getWindow().getDecorView().getRootWindowInsets();return insets!=null&&Build.VERSION.SDK_INT>=30&&insets.isVisible(WindowInsets.Type.ime())?insets.getInsets(WindowInsets.Type.ime()).bottom:0;}
  private Rect bounds(View view){Rect rect=new Rect();check(view.getGlobalVisibleRect(rect),"Control not visible: "+view.getTag());return rect;}
  private void aboveKeyboard(){Rect send=bounds(tagged("ai-chat-send")),clear=bounds(tagged("ai-chat-clear"));int top=dialog.getWindow().getDecorView().getHeight()-ime();check(ime()>0&&send.bottom<=top&&clear.bottom<=top,"Actions must be above visible soft keyboard");check(send.height()>=40*activity.getResources().getDisplayMetrics().density&&clear.height()>=40*activity.getResources().getDisplayMetrics().density,"Actions must remain fully touchable");check(send.left-clear.right>=12*activity.getResources().getDisplayMetrics().density,"Actions need a deliberate gap");}
  private void tap(View view){Rect rect=bounds(view);long now=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),0));sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,rect.centerX(),rect.centerY(),0));}
  private List<TextView> labels(View view){List<TextView> out=new ArrayList<>();if(view instanceof TextView)out.add((TextView)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)out.addAll(labels(group.getChildAt(i)));}return out;}
  private boolean has(String value){for(TextView text:labels((View)tagged("ai-chat-messages")))if(value.contentEquals(text.getText()))return true;return false;}
  private void shot(String name)throws Exception{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/chat421-"+name+".png"))){byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){}}}
  @Override public void onStart(){Bundle report=new Bundle();ExecutorService disk=Executors.newSingleThreadExecutor();CountDownLatch responseGate=new CountDownLatch(1);
    try{
      new AiVault(getTargetContext().getFilesDir()).forget();activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.yilan.news","cn.yilan.news.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Handler handler=new Handler(Looper.getMainLooper());
      AiClient client=new AiClient((config,path,request)->{calls.incrementAndGet();check(config.model.equals("ime-model")&&request.getString("model").equals("ime-model"),"Chat must stay pinned");if(hold&&!responseGate.await(15,TimeUnit.SECONDS))throw new IOException("Synthetic response gate timeout");return new JSONObject().put("model",config.model).put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content",hold?"迟到的回复":"已收到键盘内消息")))).toString();});
      session=new AiCoordinator(new AiVault(getTargetContext().getFilesDir()),handler,disk,()->{},client);session.config=new AiClient.Config("https://example.com/v1","synthetic-ime-only","ime-model","ime-profile",true);
      main(()->dialog=AiChatDialog.show(themed("day"),session,handler,ReaderTheme.forMode("day")));waitUntil(()->tagged("ai-chat-send").getWidth()>0);check(calls.get()==0,"Opening chat must not send a request");
      step="show keyboard";waitUntil(()->dialog.getWindow().getDecorView().hasWindowFocus());tap(tagged("ai-chat-input"));main(()->((EditText)tagged("ai-chat-input")).setText("键盘内发送"));waitUntil(()->ime()>0);SystemClock.sleep(250);main(this::aboveKeyboard);shot("keyboard-day");
      step="touch send with keyboard";tap(tagged("ai-chat-send"));waitUntil(()->has("已收到键盘内消息"));main(()->{aboveKeyboard();check(has("键盘内发送")&&!has("你\n键盘内发送")&&!has("你"),"User bubble must contain only original message text");check(((EditText)tagged("ai-chat-input")).hasFocus(),"Sending must keep input focus");});check(calls.get()==1,"One send touch must make one pinned request");shot("reply-day");
      step="multiline and clear";main(()->((EditText)tagged("ai-chat-input")).setText("第一行\n第二行\n第三行\n第四行\n第五行\n第六行"));SystemClock.sleep(100);main(this::aboveKeyboard);tap(tagged("ai-chat-clear"));waitUntil(()->((ViewGroup)tagged("ai-chat-messages")).getChildCount()==0&&((EditText)tagged("ai-chat-input")).getText().length()==0);main(this::aboveKeyboard);
      step="late reply after clear";hold=true;main(()->((EditText)tagged("ai-chat-input")).setText("随后清除"));tap(tagged("ai-chat-send"));waitUntil(()->calls.get()==2);tap(tagged("ai-chat-clear"));responseGate.countDown();SystemClock.sleep(250);main(()->{check(((ViewGroup)tagged("ai-chat-messages")).getChildCount()==0,"Cleared chat must not resurrect a late reply");aboveKeyboard();dialog.dismiss();});
      step="night keyboard";main(()->dialog=AiChatDialog.show(themed("night"),session,handler,ReaderTheme.forMode("night")));waitUntil(()->dialog.getWindow().getDecorView().hasWindowFocus());tap(tagged("ai-chat-input"));main(()->((EditText)tagged("ai-chat-input")).setText("夜间输入"));waitUntil(()->ime()>0);SystemClock.sleep(150);main(this::aboveKeyboard);shot("keyboard-night");
      report.putString("result","PASS");report.putString("chat","real IME and touch send/clear without hiding keyboard; multiline composer; separated actions; raw user bubbles; pinned model; clear rejects late reply; day/night");
    }catch(Throwable e){report.putString("result","FAIL");report.putString("error",step+": "+e.toString());if(dialog!=null)report.putString("imeHeight",String.valueOf(ime()));try{shot("failed-keyboard");}catch(Exception ignored){}}
    finally{responseGate.countDown();if(dialog!=null)main(()->dialog.dismiss());if(session!=null)session.close();disk.shutdown();if(activity!=null)main(()->activity.finish());}finish("PASS".equals(report.getString("result"))?-1:1,report);
  }
}
