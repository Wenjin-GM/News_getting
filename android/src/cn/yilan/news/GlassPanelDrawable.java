package cn.yilan.news;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Static glass fill and inset edge halo; no blur layer or animation per list item. */
public final class GlassPanelDrawable extends Drawable {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF panel=new RectF();
  private final float radius,unit;private final int top,bottom,edge;private int alpha=255;
  private Shader fill,stroke;
  public GlassPanelDrawable(ReaderTheme theme,boolean selected,float radius,float density){this.radius=radius;unit=density;top=theme.glassTop(selected);bottom=theme.glassBottom(selected);edge=theme.glassEdge();}
  @Override protected void onBoundsChange(Rect bounds){
    panel.set(bounds);panel.inset(3*unit,3*unit);
    fill=new LinearGradient(0,panel.top,0,Math.max(panel.bottom,panel.top+1),top,bottom,Shader.TileMode.CLAMP);
    stroke=new LinearGradient(panel.left,panel.top,panel.right,Math.max(panel.bottom,panel.top+1),new int[]{edge,0x286aaee5,edge},null,Shader.TileMode.CLAMP);
  }
  @Override public void draw(Canvas canvas){
    if(panel.width()<=0||panel.height()<=0)return;
    paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setColor(edge);
    paint.setStrokeWidth(6*unit);paint.setAlpha(alpha*9/100);canvas.drawRoundRect(panel,radius,radius,paint);
    paint.setStrokeWidth(3*unit);paint.setAlpha(alpha*20/100);canvas.drawRoundRect(panel,radius,radius,paint);
    paint.setStyle(Paint.Style.FILL);paint.setShader(fill);paint.setAlpha(alpha);canvas.drawRoundRect(panel,radius,radius,paint);
    paint.setStyle(Paint.Style.STROKE);paint.setShader(stroke);paint.setStrokeWidth(unit);canvas.drawRoundRect(panel,radius,radius,paint);
    paint.setShader(null);
  }
  @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
  @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
  @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
