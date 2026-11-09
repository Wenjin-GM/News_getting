package cn.yilan.news;
import java.util.*;
import org.json.*;
import cn.yilan.news.NewsEngine.*;

/** Shared filter semantics for native controls and JVM fixtures. */
public final class ReadingRules {
  public static void choose(Set<String> values,String value,boolean selected){
    if("all".equals(value)){if(selected)values.clear();return;}
    values.remove("all");if(selected)values.add(value);else values.remove(value);
  }
  /** Keep the AlertDialog adapter's backing array aligned before ListView can rebind rows. */
  public static void syncChoices(List<String> ids,Set<String> values,boolean[] checked){
    for(int i=0;i<ids.size();i++)checked[i]="all".equals(ids.get(i))?values.isEmpty():values.contains(ids.get(i));
  }
  public static boolean category(Set<String> selected,String category){return selected.isEmpty()||selected.contains(category);}
  public static List<Article> forDay(List<Article> input,String day){List<Article> out=new ArrayList<>();for(Article a:input)if(a.published>0&&FeaturedNews.day(a.published).equals(day))out.add(a);return out;}
  public static boolean provider(JSONObject catalog,String id,String region,Set<String> selected){
    if(!selected.isEmpty()&&!selected.contains(id))return false;
    if("all".equals(region))return true;
    JSONArray p=catalog.optJSONArray("providers");for(int i=0;p!=null&&i<p.length();i++)if(id.equals(p.optJSONObject(i).optString("id")))return region.equals(p.optJSONObject(i).optString("region"));
    JSONObject data=catalog.optJSONObject("modelData");p=data==null?null:data.optJSONArray("profiles");
    for(int i=0;p!=null&&i<p.length();i++)if(id.equals(p.optJSONObject(i).optString("id")))return region.equals(p.optJSONObject(i).optString("region"));
    return false;
  }
  public static boolean aiBoard(String id,Snapshot s){
    if(!("v2ex".equals(id)||"hackernews".equals(id))||s==null||s.fetched<=0||!s.error.isEmpty())return false;
    for(Article a:s.items)if(NewsEngine.isAIOrTech(a))return true;return false;
  }
  public static boolean aiArticle(Source source,Article a,Set<String> visibleBoards){return "ai".equals(source.category)||visibleBoards.contains(source.id)&&NewsEngine.isAIOrTech(a);}
  public static boolean archivable(Source s,Article a){return FeaturedNews.latestSource(s)||"ai".equals(s.category)||("v2ex".equals(s.id)||"hackernews".equals(s.id))&&NewsEngine.isAIOrTech(a);}
  public static String confidence(JSONObject claim){
    String c=claim.optString("confidence","unverified");JSONArray ids=claim.optJSONArray("sourceIds");
    return "verified".equals(c)&&(ids==null||ids.length()==0)?"unverified":c;
  }
  public static Set<String> toolSources(JSONObject catalog){
    Set<String> out=new HashSet<>();JSONArray tools=catalog.optJSONArray("tools");
    for(int i=0;tools!=null&&i<tools.length();i++){JSONObject t=tools.optJSONObject(i);if(t!=null&&!t.optString("sourceId").isEmpty())out.add(t.optString("sourceId"));}return out;
  }
  public static boolean platformSource(JSONObject catalog,Source s){return "ai".equals(s.category)&&!toolSources(catalog).contains(s.id);}
  private static String modelDate(JSONObject m){try{return java.time.LocalDate.parse(m.optString("releasedAt")).toString();}catch(Exception ignored){return "";}}
  public static List<JSONObject> sortedModels(JSONArray models){
    List<JSONObject> out=new ArrayList<>();for(int i=0;models!=null&&i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m!=null)out.add(m);}
    out.sort((a,b)->{int order=modelDate(b).compareTo(modelDate(a));return order!=0?order:a.optString("name").compareTo(b.optString("name"));});return out;
  }
  public static long latestModelCheck(JSONObject catalog,NewsEngine engine,Map<String,Snapshot> snapshots){
    long latest=0;for(Source source:engine.sources)if(NewsEngine.officialAI(source)&&platformSource(catalog,source)){Snapshot s=snapshots.get(source.id);if(s!=null)latest=Math.max(latest,s.fetched);}return latest;
  }
  /** A conservative release-name signal, displayed separately and never written into modelData. */
  public static List<Article> newModelNotices(JSONObject catalog,NewsEngine engine,Map<String,Snapshot> snapshots){
    JSONObject data=catalog.optJSONObject("modelData");List<Article> out=new ArrayList<>();if(data==null)return out;
    List<JSONObject> models=sortedModels(data.optJSONArray("models"));Set<String> seen=new HashSet<>();
    java.util.regex.Pattern release=java.util.regex.Pattern.compile("发布|推出|上线|release|launch|introduc|announc",java.util.regex.Pattern.CASE_INSENSITIVE);
    java.util.regex.Pattern name=java.util.regex.Pattern.compile("\\b(?:GPT|Claude|Gemini|DeepSeek|Qwen|GLM|Kimi|MiniMax)[ -]?\\d+(?:[.]\\d+)*(?:[- ](?:Pro|Flash|Max|Sonnet|Opus|Code|Air|Lite))?\\b",java.util.regex.Pattern.CASE_INSENSITIVE);
    List<Article> candidates=new ArrayList<>();for(Source source:engine.sources)if(NewsEngine.officialAI(source)&&platformSource(catalog,source)){Snapshot s=snapshots.get(source.id);if(s!=null)candidates.addAll(s.items);}
    candidates.sort((a,b)->Long.compare(b.published,a.published));
    for(Article a:candidates){if(a.published<=0||FeaturedNews.day(a.published).compareTo(data.optString("asOf"))<=0||!release.matcher(a.title).find())continue;
      java.util.regex.Matcher found=name.matcher(a.title);if(!found.find())continue;String id=FeaturedNews.normalized(found.group());boolean known=false;
      for(JSONObject m:models)if(FeaturedNews.normalized(m.optString("name")).contains(id)||FeaturedNews.normalized(m.optString("version")).equals(id)){known=true;break;}
      if(!known&&seen.add(id))out.add(a);
    }return out;
  }
  public static String previousDay(String day){return java.time.LocalDate.parse(day).minusDays(1).toString();}
  public static boolean hasDay(List<FeaturedNews.Event> events,String day){for(FeaturedNews.Event e:events)if(e.day().equals(day))return true;return false;}
  public static String olderGap(List<FeaturedNews.Event> events,long now){String oldest=null;for(FeaturedNews.Event e:events)if(oldest==null||e.day().compareTo(oldest)<0)oldest=e.day();return oldest==null?FeaturedNews.day(now):previousDay(oldest);}
}
