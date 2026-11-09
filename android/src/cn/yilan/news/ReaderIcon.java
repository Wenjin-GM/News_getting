package cn.yilan.news;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small original line icons drawn natively; no font, image download or SDK dependency. */
public final class ReaderIcon extends Drawable {
  private final String name;private final int color,size;private int alpha=255;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  public ReaderIcon(String name,int color,int size){this.name=name;this.color=color;this.size=size;}
  @Override public void draw(Canvas canvas){
    int saved=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
    paint.setColor(color);paint.setAlpha(alpha);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    Path p=new Path();
    switch(name){
      case "news":canvas.drawRoundRect(4,3,20,21,3,3,paint);canvas.drawLine(8,8,16,8,paint);canvas.drawLine(8,12,16,12,paint);canvas.drawLine(8,16,13,16,paint);break;
      case "ai":p.moveTo(12,2);p.lineTo(14.8f,9.2f);p.lineTo(22,12);p.lineTo(14.8f,14.8f);p.lineTo(12,22);p.lineTo(9.2f,14.8f);p.lineTo(2,12);p.lineTo(9.2f,9.2f);p.close();canvas.drawPath(p,paint);break;
      case "bookmark":p.moveTo(6,21);p.lineTo(6,4);p.quadTo(6,3,7,3);p.lineTo(17,3);p.quadTo(18,3,18,4);p.lineTo(18,21);p.lineTo(12,17);p.close();canvas.drawPath(p,paint);break;
      case "settings":for(int i=0;i<3;i++){float y=6+i*6,x=i==1?15:9;canvas.drawLine(3,y,x-3,y,paint);canvas.drawLine(x+3,y,21,y,paint);canvas.drawCircle(x,y,2.5f,paint);}break;
      case "search":canvas.drawCircle(10.5f,10.5f,6.5f,paint);canvas.drawLine(15.5f,15.5f,21,21,paint);break;
      case "refresh":canvas.drawArc(4,4,20,20,40,290,false,paint);p.moveTo(17,3);p.lineTo(20,6);p.lineTo(16,7);canvas.drawPath(p,paint);break;
      case "sun":canvas.drawCircle(12,12,4,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+7*(float)Math.cos(a),12+7*(float)Math.sin(a),12+9*(float)Math.cos(a),12+9*(float)Math.sin(a),paint);}break;
      case "moon":p.moveTo(19.5f,15.3f);p.cubicTo(13,18,6,11,8.7f,4.5f);p.cubicTo(1,7,3,20,12,20);p.cubicTo(15.5f,20,18.2f,18.2f,19.5f,15.3f);canvas.drawPath(p,paint);break;
      case "check":p.moveTo(5,12);p.lineTo(10,17);p.lineTo(19,7);canvas.drawPath(p,paint);break;
    }
    canvas.restoreToCount(saved);
  }
  @Override public int getIntrinsicWidth(){return size;}
  @Override public int getIntrinsicHeight(){return size;}
  @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
  @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
  @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
