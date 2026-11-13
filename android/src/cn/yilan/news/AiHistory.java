package cn.yilan.news;

import java.time.LocalDate;
import java.util.*;
import cn.yilan.news.NewsEngine.*;

/** Shared history semantics: local content is reusable without manufacturing archive history. */
public final class AiHistory {
  public static List<Article> local(Collection<Article> archive,Map<String,Snapshot> snapshots,Map<String,AiEngine.Result> results){
    Map<String,Article> rows=new LinkedHashMap<>();
    for(AiEngine.Result r:results.values())for(AiEngine.Group g:r.groups)for(Article a:g.reports)put(rows,a);
    for(Article a:archive)put(rows,a);
    for(Snapshot s:snapshots.values())for(Article a:s.items)put(rows,a);
    return new ArrayList<>(rows.values());
  }
  private static void put(Map<String,Article> rows,Article a){if(a==null||a.id.isEmpty())return;Article old=rows.get(a.id);if(old==null||a.fetched>=old.fetched)rows.put(a.id,a);}
  public static final class Plan {
    public final List<String> saved=new ArrayList<>(),local=new ArrayList<>(),fetch=new ArrayList<>(),unavailable=new ArrayList<>();
  }
  public static Plan plan(boolean ai,List<String> days,Map<String,AiEngine.Result> results,Set<String> checked,List<Article> records,NewsEngine engine,Map<String,Boolean> enabled,Set<String> boards,boolean force){
    Plan plan=new Plan();String scope=ai?AiEngine.FEATURED_AI:AiEngine.FEATURED_NEWS;
    for(String day:new LinkedHashSet<>(days)){String key=ContentDates.key(scope,day);
      if(force){plan.fetch.add(day);continue;}
      if(results.containsKey(key)){plan.saved.add(day);continue;}
      if(!AiEngine.featuredInput(ai,records,engine,enabled,boards,day,LocalDate.parse(day).plusDays(1).toString()).isEmpty())plan.local.add(day);
      else if(checked.contains(key))plan.unavailable.add(day);else plan.fetch.add(day);
    }return plan;
  }
  /** Existing non-paginated lists cannot retrieve dates older than their oldest returned item. */
  public static boolean canRecheck(Snapshot snapshot,String day){
    if(snapshot==null||snapshot.items.isEmpty()||!snapshot.error.isEmpty())return true;
    String oldest="";for(Article a:snapshot.items){String d=ContentDates.day(a);if(!d.isEmpty()&&(oldest.isEmpty()||d.compareTo(oldest)<0))oldest=d;}
    return oldest.isEmpty()||day.compareTo(oldest)>=0;
  }
  public static boolean migrate(Map<String,AiEngine.Result> results){
    boolean changed=false;
    for(String scope:new String[]{AiEngine.FEATURED_NEWS,AiEngine.FEATURED_AI}){AiEngine.Result old=results.get(scope);if(old==null)continue;Map<String,AiEngine.Result> days=new LinkedHashMap<>();
      for(AiEngine.Group group:old.groups){Map<String,AiEngine.Group> blocks=new LinkedHashMap<>();for(Article a:group.reports){String day=ContentDates.day(a);if(day.isEmpty())continue;AiEngine.Group block=blocks.computeIfAbsent(day,k->{AiEngine.Group g=new AiEngine.Group();g.title=group.title;g.reason=group.reason;return g;});block.reports.add(a);}
        for(String day:blocks.keySet()){AiEngine.Result r=days.computeIfAbsent(day,k->{AiEngine.Result v=new AiEngine.Result();v.profile=old.profile;v.model=old.model;v.at=old.at;v.since=k;v.requests=-1;v.text="升级前已存精选";v.days.add(k);return v;});r.groups.add(blocks.get(day));r.count+=blocks.get(day).reports.size();}}
      for(String day:days.keySet()){String key=ContentDates.key(scope,day);if(!results.containsKey(key)){results.put(key,days.get(day));changed=true;}}
    }return changed;
  }
}
