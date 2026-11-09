package cn.yilan.news;

import java.net.URI;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import cn.yilan.news.NewsEngine.*;

/** Conservative lexical grouping; no model, score, network request or generated summary. */
public final class FeaturedNews {
  public static final long WINDOW_MS=72L*3600000;
  public static final double MIN_SIMILARITY=0.76;
  private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
  public static boolean latestSource(Source s){return !Arrays.asList("v2ex","v2ex-latest","hackernews").contains(s.id)&&Arrays.asList("news","tech","finance").contains(s.category);}
  public static boolean stationSource(String id){return "toutiao".equals(id)||"tieba".equals(id);}
  public static List<Article> hotItems(String id,Snapshot snapshot,boolean ai,Set<String> aiURLs){
    if(!ai&&!stationSource(id))return new ArrayList<>();
    List<Article> items=NewsEngine.hotItems(id,snapshot,ai,aiURLs);
    items.removeIf(a->"v2ex".equals(id)&&!NewsEngine.isAIOrTech(a));return items;
  }
  public static String day(long time){return Instant.ofEpochMilli(time).atZone(SHANGHAI).toLocalDate().toString();}
  public static String initialDay(long now){return Instant.ofEpochMilli(now).atZone(SHANGHAI).toLocalDate().minusDays(6).toString();}
  public static String earlierDay(String day){return LocalDate.parse(day).minusDays(6).toString();}
  public static String normalized(String title){return Normalizer.normalize(title,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
  private static Set<String> grams(String title){
    Set<String> out=new HashSet<>();int[] points=normalized(title).codePoints().toArray();
    for(int i=1;i<points.length;i++)out.add(new String(points,i-1,2));return out;
  }
  private static Set<String> numbers(String title){Set<String> out=new HashSet<>();Matcher m=Pattern.compile("[0-9]+(?:[.][0-9]+)*").matcher(Normalizer.normalize(title,Normalizer.Form.NFKC));while(m.find())out.add(m.group());return out;}
  public static boolean similar(String a,String b){
    String x=normalized(a),y=normalized(b);if(x.length()<8||y.length()<8)return false;
    if(!numbers(a).equals(numbers(b)))return false;
    Set<String> ga=grams(a),gb=grams(b);int common=0;for(String g:ga)if(gb.contains(g))common++;
    return common>=6&&2.0*common/(ga.size()+gb.size())>=MIN_SIMILARITY;
  }
  public static String publisher(Source s){
    if(!s.provider.isEmpty())return "provider:"+s.provider;
    try{String host=new URI(s.home).getHost();if(host!=null)return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");}catch(Exception ignored){}
    return s.id;
  }
  public static final class Event {
    public final List<Article> reports=new ArrayList<>();
    public final Set<String> publishers=new HashSet<>();
    private final Set<String> titles=new HashSet<>();
    public long latest;
    public String title(){return reports.get(0).title;}
    public String day(){return FeaturedNews.day(latest);}
  }
  public static List<Event> group(List<Article> input,Map<String,Source> sources,Map<String,Boolean> enabled){
    return group(input,sources,enabled,false,Collections.emptySet());
  }
  public static List<Event> group(List<Article> input,Map<String,Source> sources,Map<String,Boolean> enabled,boolean ai,Set<String> visibleBoards){
    List<Article> articles=new ArrayList<>();Set<String> links=new HashSet<>();
    input.stream().sorted((a,b)->Long.compare(b.published,a.published)).forEach(a->{
      Source s=sources.get(a.source);
      if(s!=null&&(ai?ReadingRules.aiArticle(s,a,visibleBoards):latestSource(s))&&Boolean.TRUE.equals(enabled.get(a.source))&&a.published>0&&links.add(a.source+"|"+a.url))articles.add(a);
    });
    List<Event> groups=new ArrayList<>();Map<String,Set<Integer>> index=new HashMap<>();
    for(Article a:articles){
      Set<String> keys=grams(a.title);Set<Integer> candidates=new TreeSet<>();for(String key:keys){Set<Integer> ids=index.get(key);if(ids!=null)candidates.addAll(ids);}
      Event target=null;
      for(int id:candidates){Event e=groups.get(id);if(e.latest-a.published>WINDOW_MS)continue;boolean match=true;
        for(String title:e.titles)if(!similar(a.title,title)){match=false;break;}
        if(match){target=e;break;}
      }
      if(target==null){target=new Event();target.latest=a.published;int id=groups.size();groups.add(target);for(String key:keys)index.computeIfAbsent(key,k->new HashSet<>()).add(id);}
      target.reports.add(a);target.titles.add(a.title);target.publishers.add(publisher(sources.get(a.source)));
    }
    groups.removeIf(e->e.publishers.size()<2);return groups;
  }
  public static Map<String,List<Event>> days(List<Event> events,String since){
    Map<String,List<Event>> out=new TreeMap<>(Collections.reverseOrder());
    for(Event e:events)if(e.day().compareTo(since)>=0)out.computeIfAbsent(e.day(),d->new ArrayList<>()).add(e);
    return out;
  }
}
