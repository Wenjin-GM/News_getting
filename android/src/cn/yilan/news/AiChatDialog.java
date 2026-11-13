package cn.yilan.news;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Standalone chat: only the transcript scrolls; the composer remains above the IME. */
public final class AiChatDialog {
  public static Dialog show(Context context,AiCoordinator session,Handler handler,ReaderTheme theme){
    if(!session.enabled())throw new IllegalStateException("AI model not enabled");
    float density=context.getResources().getDisplayMetrics().density;int pad=Math.round(16*density),touch=Math.round(44*density);
    Dialog dialog=new Dialog(context,theme.dark?R.style.Reader_Night:R.style.Reader_Day);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout root=new LinearLayout(context);root.setOrientation(1);root.setTag("ai-chat-root");root.setPadding(pad,pad,pad,pad);root.setBackgroundColor(theme.surface);
    LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);TextView model=new TextView(context);model.setText(session.config.model);model.setTextSize(15);model.setTextColor(theme.ink);model.setTypeface(null,Typeface.BOLD);model.setSingleLine(true);model.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(model,new LinearLayout.LayoutParams(0,touch,1));ImageButton close=new ImageButton(context);close.setImageDrawable(new ReaderIcon("close",theme.muted,Math.round(20*density)));close.setBackgroundColor(Color.TRANSPARENT);close.setContentDescription("关闭对话");close.setOnClickListener(v->dialog.dismiss());header.addView(close,new LinearLayout.LayoutParams(touch,touch));root.addView(header);
    View rule=new View(context);rule.setBackgroundColor(theme.line);root.addView(rule,new LinearLayout.LayoutParams(-1,Math.max(1,Math.round(density))));LinearLayout panel=new LinearLayout(context);panel.setOrientation(1);root.addView(panel,new LinearLayout.LayoutParams(-1,0,1));AiChatPanel chat=new AiChatPanel(context,panel,session,handler,theme);dialog.setContentView(root);
    Window window=dialog.getWindow();if(window!=null){window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);window.setDimAmount(theme.dark?.65f:.25f);window.setBackgroundDrawableResource(android.R.color.transparent);}
    android.view.ViewTreeObserver.OnGlobalLayoutListener fallback=()->{if(Build.VERSION.SDK_INT>=30||!dialog.isShowing())return;Rect visible=new Rect();root.getWindowVisibleDisplayFrame(visible);int[] pos=new int[2];root.getLocationOnScreen(pos);int height=Math.max(1,visible.bottom-Math.max(visible.top,pos[1]));ViewGroup.LayoutParams lp=root.getLayoutParams();if(lp!=null&&lp.height!=height){lp.height=height;root.setLayoutParams(lp);}chat.compact(root.getRootView().getHeight()-visible.bottom>Math.round(120*density));};
    if(Build.VERSION.SDK_INT>=30&&window!=null){window.setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()),ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+Math.max(bars.bottom,ime.bottom));chat.compact(insets.isVisible(WindowInsets.Type.ime()));return insets;});}else root.getViewTreeObserver().addOnGlobalLayoutListener(fallback);
    dialog.setOnDismissListener(d->{chat.close();if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(fallback);((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);});
    dialog.show();if(window!=null){window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels,Math.round(680*density)),-1);window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);window.setStatusBarColor(theme.surface);window.setNavigationBarColor(theme.surface);if(Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);int light=theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;window.getDecorView().setSystemUiVisibility(light);if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=window.getInsetsController();if(controller!=null)controller.setSystemBarsAppearance(theme.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}}root.requestApplyInsets();return dialog;
  }
}
