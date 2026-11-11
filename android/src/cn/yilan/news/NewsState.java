package cn.yilan.news;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import cn.yilan.news.NewsEngine.*;

/** UI-independent schema-3 persistence. New preferences default safely for old files. */
public final class NewsState {
  public final Map<String,Snapshot> snapshots=new LinkedHashMap<>();
  public final Map<String,Snapshot> linuxTabs=new LinkedHashMap<>();
  public final Map<String,Boolean> sourceEnabled=new LinkedHashMap<>();
  public final List<Article> favorites=new ArrayList<>();
  public final Set<String> read=new LinkedHashSet<>();
  public boolean compact,large,unread,hotMode;
  public String themeMode="day";
  private static final String[] LINUX_TAB_KEYS={"default","views","latest"};

  /** Settings summarizes the most recently checked sub-page, not the retired feeds entry. */
  public Snapshot latestLinuxSnapshot(){
    Snapshot latest=null;for(Snapshot s:linuxTabs.values())
      if(latest==null||s.checked>latest.checked||s.checked==latest.checked&&s.fetched>latest.fetched)latest=s;
    return latest;
  }

  public void restore(NewsEngine engine,JSONObject j){
    snapshots.clear();linuxTabs.clear();sourceEnabled.clear();favorites.clear();read.clear();
    compact=large=unread=hotMode=false;
    themeMode="day";
    for(Source s:engine.sources)sourceEnabled.put(s.id,s.defaultEnabled);
    if(j==null)return;
    JSONObject feeds=j.optJSONObject("feeds"),enabled=j.optJSONObject("enabled");
    for(Source s:engine.sources){
      JSONObject feed=feeds==null?null:feeds.optJSONObject(s.id);
      if(feed!=null){
        if("linuxdo".equals(s.id)){
          // Legacy single linux.do snapshot migrates to the default tab.
          if(linuxTabs.isEmpty())linuxTabs.put("default",Snapshot.from("linuxdo",feed));
        }else snapshots.put(s.id,Snapshot.from(s.id,feed));
      }
      if(enabled!=null&&enabled.opt(s.id) instanceof Boolean)sourceEnabled.put(s.id,enabled.optBoolean(s.id));
    }
    JSONObject tabs=j.optJSONObject("linuxTabs");
    if(tabs!=null)for(String key:LINUX_TAB_KEYS)
      if(tabs.optJSONObject(key)!=null)linuxTabs.put(key,Snapshot.from("linuxdo",tabs.optJSONObject(key)));
    Set<String> favoriteIds=new HashSet<>();
    JSONArray fav=j.optJSONArray("favorites");
    for(int i=0;fav!=null&&i<Math.min(500,fav.length());i++){
      Article a=Article.from(fav.optJSONObject(i));
      if(a!=null&&favoriteIds.add(a.id))favorites.add(a);
    }
    JSONArray ids=j.optJSONArray("read");
    for(int i=0;ids!=null&&i<Math.min(2000,ids.length());i++){
      String id=ids.optString(i);if(id.length()<=128)read.add(id);
    }
    compact=j.optBoolean("compact");large=j.optBoolean("large");unread=j.optBoolean("unread");hotMode=j.optBoolean("hotMode");
    themeMode=ReaderTheme.mode(j.optString("themeMode"));
  }

  public JSONObject json()throws org.json.JSONException{
    JSONObject j=new JSONObject(),feeds=new JSONObject(),enabled=new JSONObject(),tabs=new JSONObject();
    for(Map.Entry<String,Snapshot> e:snapshots.entrySet())feeds.put(e.getKey(),e.getValue().json());
    for(Map.Entry<String,Snapshot> e:linuxTabs.entrySet())tabs.put(e.getKey(),e.getValue().json());
    for(Map.Entry<String,Boolean> e:sourceEnabled.entrySet())enabled.put(e.getKey(),e.getValue());
    JSONArray fav=new JSONArray(),ids=new JSONArray();
    for(Article a:favorites)fav.put(a.json());
    for(String id:read)ids.put(id);
    return j.put("schema",3).put("feeds",feeds).put("linuxTabs",tabs).put("enabled",enabled)
      .put("favorites",fav).put("read",ids)
      .put("compact",compact).put("large",large).put("unread",unread).put("hotMode",hotMode)
      .put("themeMode",ReaderTheme.mode(themeMode));
  }
}
