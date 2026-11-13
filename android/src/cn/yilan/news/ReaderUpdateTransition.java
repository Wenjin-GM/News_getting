package cn.yilan.news;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.*;

/** Interruptible width morph. Only the two existing controls move; requests and progress stay independent. */
public final class ReaderUpdateTransition {
  private final LinearLayout bar;private final View search;private final Button update;private final int gap;
  private ValueAnimator animation;private boolean initialized,working;
  public ReaderUpdateTransition(LinearLayout bar,View search,Button update,int gap){this.bar=bar;this.search=search;this.update=update;this.gap=gap;}
  public boolean isAnimating(){return animation!=null&&animation.isRunning();}
  public void setWorking(boolean next,boolean animate){
    if(initialized&&working==next){if(!animate)finish();return;}
    boolean first=!initialized;initialized=true;working=next;cancel();
    int width=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight();
    if(first||!animate||width<=0){finish();return;}
    int idle=Math.min(width,Math.max(gap*6,Math.round(update.getPaint().measureText("更新"))+update.getCompoundPaddingLeft()+update.getCompoundPaddingRight()));
    int from=Math.max(1,update.getWidth()),to=working?width:idle;float fromAlpha=search.getVisibility()==View.VISIBLE?search.getAlpha():0;
    search.setVisibility(View.VISIBLE);search.setEnabled(!working);
    animation=ValueAnimator.ofFloat(0,1);animation.setDuration(320);animation.setInterpolator(new PathInterpolator(.2f,0,0,1));
    animation.addUpdateListener(value->{float t=(Float)value.getAnimatedValue();LinearLayout.LayoutParams u=(LinearLayout.LayoutParams)update.getLayoutParams();u.width=Math.round(from+(to-from)*t);update.setLayoutParams(u);float alpha=fromAlpha+((working?0:1)-fromAlpha)*t;search.setAlpha(alpha);LinearLayout.LayoutParams s=(LinearLayout.LayoutParams)search.getLayoutParams();s.rightMargin=Math.round(gap*alpha);search.setLayoutParams(s);});
    animation.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator value){animation=null;settle();}});animation.start();
  }
  private void cancel(){if(animation!=null){animation.removeAllListeners();animation.removeAllUpdateListeners();animation.cancel();animation=null;}}
  public void finish(){cancel();if(initialized)settle();}
  private void settle(){search.setAlpha(1);search.setVisibility(working?View.GONE:View.VISIBLE);search.setEnabled(!working);LinearLayout.LayoutParams s=(LinearLayout.LayoutParams)search.getLayoutParams();s.rightMargin=gap;search.setLayoutParams(s);LinearLayout.LayoutParams u=(LinearLayout.LayoutParams)update.getLayoutParams();u.width=working?-1:-2;update.setLayoutParams(u);}
}
