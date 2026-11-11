package cn.yilan.news;

import java.util.*;

/** Bounded process-memory LRU. Oversized text can still be displayed, but is not retained. */
public final class ArticleTextCache {
  public static final int MAX_ENTRIES=24,MAX_CHARS=2*1024*1024;
  private final int entries,capacity;
  private int chars;
  private final LinkedHashMap<String,String> values=new LinkedHashMap<>(16,0.75f,true);
  public ArticleTextCache(){this(MAX_ENTRIES,MAX_CHARS);}
  ArticleTextCache(int entries,int capacity){this.entries=entries;this.capacity=capacity;}
  public synchronized String get(String key){return values.get(key);}
  public synchronized void put(String key,String text){
    if(key==null||text==null||text.isEmpty()||text.length()>capacity)return;
    String old=values.put(key,text);chars+=text.length()-(old==null?0:old.length());
    Iterator<Map.Entry<String,String>> it=values.entrySet().iterator();
    while((values.size()>entries||chars>capacity)&&it.hasNext()){
      chars-=it.next().getValue().length();it.remove();
    }
  }
  public synchronized void clear(){values.clear();chars=0;}
  synchronized int size(){return values.size();}
  synchronized int chars(){return chars;}
}
