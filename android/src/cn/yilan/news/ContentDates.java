package cn.yilan.news;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import cn.yilan.news.NewsEngine.*;

/** Publication dates in Shanghai; acquisition times are never substituted. */
public final class ContentDates {
  public static String day(Article a){return a.published>0?FeaturedNews.day(a.published):"";}
  public static String key(String scope,String day){LocalDate.parse(day);return scope+"@"+day;}
  public static String scope(String key){int i=key.indexOf('@');return i<0?key:key.substring(0,i);}
  public static String date(String key){int i=key.indexOf('@');if(i<0)return "";try{return LocalDate.parse(key.substring(i+1)).toString();}catch(Exception e){return "";}}
  public static List<String> older(String floor){List<String> out=new ArrayList<>();LocalDate day=LocalDate.parse(floor);for(int i=1;i<=3;i++)out.add(day.minusDays(i).toString());return out;}
  public static boolean matches(Article a,Predicate<String> dates){String day=day(a);return !day.isEmpty()&&dates.test(day);}
  public static int clearSnapshots(Collection<Snapshot> snapshots,Predicate<String> dates){int count=0;for(Snapshot s:snapshots){int before=s.items.size();s.items.removeIf(a->matches(a,dates));count+=before-s.items.size();if(before!=s.items.size()){s.cached=true;if(s.items.isEmpty()){s.fetched=0;s.checked=0;}}}return count;}
  public static Map<String,Integer> counts(Collection<Article> articles){Map<String,Set<String>> ids=new TreeMap<>();for(Article a:articles){String day=day(a);if(!day.isEmpty())ids.computeIfAbsent(day,k->new HashSet<>()).add(a.id);}Map<String,Integer> out=new TreeMap<>();for(String day:ids.keySet())out.put(day,ids.get(day).size());return out;}
}
