package cn.yilan.news;

import java.util.*;
import org.json.*;
import cn.yilan.news.NewsEngine.*;

/** Presentation groups describe publishers, never server location or reachability. */
public final class SourceFilters {
  public static final String[] TAGS={"all","general","digital","development","science","business","culture"};
  public static final String[] TAG_NAMES={"全部标签","综合资讯","数码科技","开发与开源","科学研究","财经商业","社区文化"};
  private static Set<String> ids(String value){return new HashSet<>(Arrays.asList(value.split(" ")));}
  private static final Set<String> GLOBAL=ids("nytimes wsj washingtonpost guardian ft tass v2ex v2ex-latest hackernews france24 the-verge techcrunch ars-technica wired 9to5google android-authority techmeme mit-tech-review lobsters slashdot nature science-mag phys-org npr cnet stackoverflow-blog github-blog new-scientist science-daily hacker-noon");
  private static final Set<String> CHINA=ids("chinanews chinanews-world ithome ifanr thepaper wallstreetcn baidu toutiao douyin bilibili bilibili-video weibo zhihu tieba linuxdo ai-media-ithome ai-media-ifanr ai-aihot xinhuanet sspai infoq-cn cnblogs tmtpost qbitai gcores ruanyifeng");
  private static final Set<String> SCIENCE=ids("nature science-mag phys-org new-scientist science-daily mit-tech-review");
  private static final Set<String> DEVELOPMENT=ids("infoq-cn cnblogs ruanyifeng lobsters slashdot stackoverflow-blog github-blog hacker-noon");
  public static String region(JSONObject catalog,Source source){
    if(source==null)return "unknown";
    if(CHINA.contains(source.id))return "china";
    if(GLOBAL.contains(source.id))return "global";
    if(!source.provider.isEmpty())for(String region:new String[]{"china","global"})
      if(ReadingRules.provider(catalog,source.provider,region,Collections.emptySet()))return region;
    return "unknown";
  }
  public static boolean inRegion(JSONObject catalog,Source source,String selected){return "all".equals(selected)||selected.equals(region(catalog,source));}
  public static Set<String> tags(Source source){
    Set<String> out=new LinkedHashSet<>();if(source==null)return out;
    if(SCIENCE.contains(source.id))out.add("science");
    else if("tech".equals(source.category))out.add("digital");
    if(DEVELOPMENT.contains(source.id))out.add("development");
    if("news".equals(source.category))out.add("general");
    if("finance".equals(source.category))out.add("business");
    if("community".equals(source.category)&&!DEVELOPMENT.contains(source.id))out.add("culture");
    if("sspai".equals(source.id))out.add("digital");
    return out;
  }
  public static boolean news(JSONObject catalog,Source source,String selectedRegion,Set<String> selectedTags){
    return inRegion(catalog,source,selectedRegion)&&(selectedTags.isEmpty()||!Collections.disjoint(tags(source),selectedTags));
  }
  public static boolean platform(JSONObject catalog,Source source,String selectedRegion,Set<String> selectedSources){
    return source!=null&&inRegion(catalog,source,selectedRegion)&&(selectedSources.isEmpty()||selectedSources.contains(source.id));
  }
  public static List<Source> settingsOrder(List<Source> sources,Map<String,Snapshot> snapshots,Snapshot linux){
    List<Source> out=new ArrayList<>(sources);
    // Stable sort preserves registry order for equal status/count.
    out.sort((a,b)->{
      if(a.id.equals("linuxdo")!=b.id.equals("linuxdo"))return a.id.equals("linuxdo")?-1:1;
      Snapshot x=a.id.equals("linuxdo")?linux:snapshots.get(a.id),y=b.id.equals("linuxdo")?linux:snapshots.get(b.id);
      boolean failedX=x!=null&&!x.error.isEmpty(),failedY=y!=null&&!y.error.isEmpty();
      if(failedX!=failedY)return failedX?-1:1;
      return Integer.compare(y==null?0:y.items.size(),x==null?0:x.items.size());
    });return out;
  }
}
