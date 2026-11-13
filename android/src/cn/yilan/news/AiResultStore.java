package cn.yilan.news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Immutable daily blobs + atomic head. A failed commit never replaces the previous head. */
public final class AiResultStore {
  public static final int HEAD_LIMIT=6*1024*1024,DAY_LIMIT=6*1024*1024,TOTAL_LIMIT=64*1024*1024;
  public interface Files {
    byte[] read(String name,int limit)throws Exception;
    void write(String name,byte[] data)throws Exception;
    Set<String> names();
    void delete(String name);
  }
  private final Files files;private boolean blocked;
  public AiResultStore(Files files){this.files=files;}
  private static String name(String hash)throws IOException{if(!hash.matches("[a-f0-9]{64}"))throw new IOException("历史索引无效");return "ai-day-"+hash+".json";}
  private static String hash(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
  private static boolean daily(String key){return !ContentDates.date(key).isEmpty()&&(ContentDates.scope(key).equals(AiEngine.FEATURED_NEWS)||ContentDates.scope(key).equals(AiEngine.FEATURED_AI));}
  private static String dailyFile(String file){String base=file.replaceFirst("\\.(?:bak|new)$","");return base.matches("ai-day-[a-f0-9]{64}\\.json")?base:"";}
  public JSONObject load()throws Exception{
    try{byte[] bytes=files.read("ai-results-v1.json",HEAD_LIMIT);if(bytes==null){for(String file:files.names())if(!dailyFile(file).isEmpty())throw new IOException("历史索引缺失，原文件保留");blocked=false;return new JSONObject();}JSONObject head=new JSONObject(new String(bytes,StandardCharsets.UTF_8));JSONObject refs=head.optJSONObject("dailyFiles"),rows=head.optJSONObject("results");if(rows==null||head.has("dailyFiles")&&refs==null)throw new IOException("AI缓存结构无效");
      int total=bytes.length;if(refs!=null)for(Iterator<String> it=refs.keys();it.hasNext();){String key=it.next();if(!daily(key))throw new IOException("历史日期无效");String digest=refs.getString(key);byte[] data=files.read(name(digest),DAY_LIMIT);if(data==null||!digest.equals(hash(data)))throw new IOException("历史文件缺失或损坏");total+=data.length;if(total>TOTAL_LIMIT)throw new IOException("历史缓存过大");JSONObject row=new JSONObject(new String(data,StandardCharsets.UTF_8));AiEngine.Result.from(row);rows.put(key,row);}
      for(String key:keys(rows))AiEngine.Result.from(rows.getJSONObject(key));
      head.remove("dailyFiles");blocked=false;return head;
    }catch(Exception e){blocked=true;throw e;}
  }
  public void save(JSONObject data)throws Exception{
    if(blocked)throw new IOException("读取失败的旧AI文件受保护");
    JSONObject head=new JSONObject(data.toString()),rows=head.optJSONObject("results"),refs=new JSONObject();Map<String,byte[]> blobs=new LinkedHashMap<>();int total=0;
    if(rows!=null)for(String key:keys(rows))if(daily(key)){byte[] bytes=rows.getJSONObject(key).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>DAY_LIMIT)throw new IOException("单日精选过大");String digest=hash(bytes);refs.put(key,digest);blobs.put(name(digest),bytes);total+=bytes.length;rows.remove(key);}
    // Today's compatibility alias is reconstructed on load; do not store the same report twice.
    if(rows!=null)for(String scope:new String[]{AiEngine.FEATURED_NEWS,AiEngine.FEATURED_AI}){JSONObject alias=rows.optJSONObject(scope);if(alias!=null){String day=alias.optString("since");if(!day.isEmpty()&&refs.has(ContentDates.key(scope,day)))rows.remove(scope);}}
    head.put("dailyFiles",refs);byte[] bytes=head.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>HEAD_LIMIT||total+bytes.length>TOTAL_LIMIT)throw new IOException("AI缓存容量已满");
    Set<String> previous=files.names();for(Map.Entry<String,byte[]> blob:blobs.entrySet()){byte[] existing=files.read(blob.getKey(),DAY_LIMIT);if(existing==null||!Arrays.equals(existing,blob.getValue()))files.write(blob.getKey(),blob.getValue());}
    files.write("ai-results-v1.json",bytes);
    for(String old:previous){String base=dailyFile(old);if(!base.isEmpty()&&!blobs.containsKey(base))files.delete(base);}
  }
  private static List<String> keys(JSONObject object){List<String> out=new ArrayList<>();for(Iterator<String> it=object.keys();it.hasNext();)out.add(it.next());return out;}
  public void clear(){files.delete("ai-results-v1.json");for(String old:files.names()){String base=dailyFile(old);if(!base.isEmpty())files.delete(base);}blocked=false;}
}
