package cn.yilan.news;

import android.content.Context;
import android.view.*;
import android.widget.EditText;

/** Keyboard Back releases the reader search field instead of leaving an active cursor. */
public final class SearchInput extends EditText {
  private final Runnable dismissed;
  public SearchInput(Context context,Runnable dismissed){super(context);this.dismissed=dismissed;}
  @Override public boolean onKeyPreIme(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP)dismissed.run();return super.onKeyPreIme(key,event);}
}
