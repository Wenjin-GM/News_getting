package cn.yilan.news;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** One bounded static bitmap at a time, center-cropped under a themed gradient scrim. */
public final class ReaderBackdrop extends View {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  private final RectF target=new RectF();private final int[] resourceIds;
  private Bitmap bitmap;private ReaderTheme palette;private int page=-1;private Shader scrim;
  public ReaderBackdrop(Context context){
    super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setFocusable(false);
    resourceIds=new int[]{R.drawable.scene_news,R.drawable.scene_ai,R.drawable.scene_saved};
  }
  public void showPage(int next,ReaderTheme theme){
    if(page==next&&palette!=null&&palette.dark==theme.dark)return;
    if(page!=next){bitmap=null;if(next>=0&&next<resourceIds.length){
      try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeResource(getResources(),resourceIds[next],options);
        options.inSampleSize=1;while((long)(options.outWidth/options.inSampleSize)*(options.outHeight/options.inSampleSize)>2000000)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;options.inScaled=false;bitmap=BitmapFactory.decodeResource(getResources(),resourceIds[next],options);
      }catch(OutOfMemoryError|RuntimeException ignored){bitmap=null;}
    }}
    page=next;palette=theme;updateScrim();invalidate();
  }
  private void updateScrim(){if(palette!=null)scrim=new LinearGradient(0,0,0,Math.max(1,getHeight()),palette.scrimTop(),palette.scrimBottom(),Shader.TileMode.CLAMP);}
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){updateScrim();}
  @Override protected void onDraw(Canvas canvas){
    if(palette==null)return;canvas.drawColor(palette.background);paint.setColor(Color.WHITE);paint.setAlpha(255);
    if(page==3)return;
    if(bitmap!=null){float scale=Math.max(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;target.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);paint.setShader(null);canvas.drawBitmap(bitmap,null,target,paint);}
    paint.setShader(scrim);canvas.drawRect(0,0,getWidth(),getHeight(),paint);paint.setShader(null);
    // Short peripheral ticks suggest a technical grid without crossing the reading column.
    float dp=getResources().getDisplayMetrics().density;paint.setColor(palette.dark?0x288ccfff:0x22295d87);paint.setStrokeWidth(dp);
    for(float y=80*dp;y<getHeight();y+=72*dp){canvas.drawLine(0,y,12*dp,y,paint);canvas.drawLine(getWidth()-12*dp,y,getWidth(),y,paint);}
  }
}
