package cn.yilan.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Bounded local successful-response cache. Stores hashes/output, never API credentials or input bodies. */
public final class AiBatchCache {
  private static final int MAX_ENTRIES=96,MAX_BYTES=384*1024;
  private final LinkedHashMap<String,JSONObject> rows=new LinkedHashMap<>(16,.75f,true);
  public static String key(AiClient.Config config,String rule,String input,int tokens)throws Exception{
    String value="ai402-v1\n"+config.id+"\n"+config.url+"\n"+config.model+"\n"+tokens+"\n"+rule+"\n"+input;
    byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
  }
  public synchronized String get(String key){JSONObject row=rows.get(key);if(row==null)return null;long age=System.currentTimeMillis()-row.optLong("at");if(age<0||age>7L*86400000){rows.remove(key);return null;}return row.optString("text");}
  public synchronized void put(String key,String value)throws Exception{if(value.getBytes(StandardCharsets.UTF_8).length>32768)return;rows.put(key,new JSONObject().put("text",value).put("at",System.currentTimeMillis()));trim();}
  public synchronized void remove(String key){rows.remove(key);}
  public synchronized void clear(){rows.clear();}
  private void trim(){int bytes=0;for(JSONObject row:rows.values())bytes+=row.toString().getBytes(StandardCharsets.UTF_8).length;while(rows.size()>MAX_ENTRIES||bytes>MAX_BYTES){String first=rows.keySet().iterator().next();bytes-=rows.remove(first).toString().getBytes(StandardCharsets.UTF_8).length;}}
  public synchronized JSONObject json()throws Exception{JSONObject out=new JSONObject();for(Map.Entry<String,JSONObject> row:rows.entrySet())out.put(row.getKey(),row.getValue());return out;}
  public synchronized void restore(JSONObject data){rows.clear();if(data==null)return;for(Iterator<String> it=data.keys();it.hasNext()&&rows.size()<MAX_ENTRIES;){String key=it.next();JSONObject row=data.optJSONObject(key);if(key.matches("[0-9a-f]{64}")&&row!=null&&row.opt("text") instanceof String&&row.optString("text").length()<=32768)rows.put(key,row);}trim();}
}
