package cn.yilan.news;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import cn.yilan.news.NewsEngine.*;

/** Bounded device-only history. Never backfills from old snapshots or favorites. */
public final class NewsArchive {
  public static final int MAX_ITEMS=12000,MAX_BYTES=16*1024*1024;
  public static final long KEEP_MS=7L*86400000;
  private final LinkedHashMap<String,Article> records=new LinkedHashMap<>();
  private final Map<String,Long> firstSeen=new HashMap<>();
  private final Map<String,Integer> sizes=new HashMap<>();
  private int bytes=1024;
  public long revision;
  public boolean incomplete;
  public List<Article> articles(){return new ArrayList<>(records.values());}
  public int deleteDates(java.util.function.Predicate<String> dates){int count=0;for(String id:new ArrayList<>(records.keySet()))if(ContentDates.matches(records.get(id),dates)){remove(id);count++;}if(count>0)revision++;return count;}
  private int size(Article a,long seen){return a.json().toString().getBytes(StandardCharsets.UTF_8).length+100;}
  private long retainedAt(String id){return Math.max(firstSeen.get(id),records.get(id).fetched);}
  private void remove(String id){bytes-=sizes.remove(id);records.remove(id);firstSeen.remove(id);}
  private boolean room(int extra,int count,long now){
    if(bytes+extra<=MAX_BYTES&&records.size()+count<=MAX_ITEMS)return true;
    List<String> oldest=new ArrayList<>(records.keySet());oldest.sort(Comparator.comparingLong(this::retainedAt));
    for(String id:oldest){
      if(now-retainedAt(id)<KEEP_MS)break;
      remove(id);
      if(bytes+extra<=MAX_BYTES&&records.size()+count<=MAX_ITEMS)return true;
    }
    return false;
  }
  public void accept(Snapshot snapshot,NewsEngine engine,long now){
    Source s=engine.sourceMap.get(snapshot.source);
    if(s==null||snapshot.cached||!snapshot.error.isEmpty())return;
    for(Article a:snapshot.items){
      if(!s.id.equals(a.source)||!ReadingRules.archivable(s,a))continue;
      Article copy=Article.from(a.json());if(copy==null)continue;
      long seen=firstSeen.containsKey(a.id)?firstSeen.get(a.id):now;
      int n=size(copy,seen),old=sizes.containsKey(a.id)?sizes.get(a.id):0;
      // Updates cannot evict the record being updated; only bounded additions need pruning.
      if(records.containsKey(a.id)){
        if(bytes+n-old>MAX_BYTES){incomplete=true;continue;}
      }else if(!room(n,1,now)){incomplete=true;continue;}
      records.put(a.id,copy);firstSeen.put(a.id,seen);sizes.put(a.id,n);bytes+=n-old;
    }
    revision++;
  }
  public void restore(JSONObject j,NewsEngine engine){
    records.clear();firstSeen.clear();sizes.clear();bytes=1024;incomplete=false;
    if(j==null)return;
    incomplete=j.optBoolean("incomplete");JSONArray rows=j.optJSONArray("records");
    for(int i=0;rows!=null&&i<Math.min(MAX_ITEMS,rows.length());i++){
      JSONObject row=rows.optJSONObject(i);if(row==null)continue;Article a=Article.from(row.optJSONObject("article"));
      if(a==null||records.containsKey(a.id))continue;Source s=engine.sourceMap.get(a.source);
      long seen=row.optLong("firstSeen");if(s==null||!ReadingRules.archivable(s,a)||seen<=0)continue;
      int n=size(a,seen);if(bytes+n>MAX_BYTES){incomplete=true;break;}
      records.put(a.id,a);firstSeen.put(a.id,seen);sizes.put(a.id,n);bytes+=n;
    }
    revision++;
  }
  /** Validate before replacing any in-memory records; an unreadable file must never become an empty archive. */
  public void restoreChecked(JSONObject j,NewsEngine engine)throws JSONException{
    if(j==null||j.optInt("schema")!=1||j.optJSONArray("records")==null)throw new JSONException("Invalid archive");JSONArray rows=j.getJSONArray("records");
    for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null||Article.from(row.optJSONObject("article"))==null||row.optLong("firstSeen")<=0)throw new JSONException("Invalid archive record");}
    restore(j,engine);
  }
  public JSONObject json()throws JSONException{
    JSONArray rows=new JSONArray();for(Article a:records.values())rows.put(new JSONObject().put("article",a.json()).put("firstSeen",firstSeen.get(a.id)));
    return new JSONObject().put("schema",1).put("incomplete",incomplete).put("records",rows);
  }
}
