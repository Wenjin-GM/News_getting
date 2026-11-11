package cn.yilan.news;

import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Keys never enter news state, generated assets, backups, logs or APK sharing. */
public final class AiVault {
  private static final String ALIAS="yilan-ai-settings-v1";
  private final AtomicFile config,results;
  private long revision;
  public AiVault(File directory){config=new AtomicFile(new File(directory,"ai-settings-v1.enc"));results=new AtomicFile(new File(directory,"ai-results-v1.json"));}
  private SecretKey key()throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    if(!store.containsAlias(ALIAS)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();}
    return (SecretKey)store.getKey(ALIAS,null);
  }
  private static void write(AtomicFile target,byte[] bytes)throws Exception{FileOutputStream out=null;try{out=target.startWrite();out.write(bytes);target.finishWrite(out);}catch(Exception e){if(out!=null)target.failWrite(out);throw e;}}
  public synchronized void save(AiClient.Config value)throws Exception{
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] encrypted=cipher.doFinal(value.secretJSON().toString().getBytes(StandardCharsets.UTF_8));
    JSONObject envelope=new JSONObject().put("version",1).put("iv",android.util.Base64.encodeToString(cipher.getIV(),android.util.Base64.NO_WRAP)).put("data",android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP));write(config,envelope.toString().getBytes(StandardCharsets.UTF_8));revision++;
  }
  public synchronized AiClient.Config load()throws Exception{
    if(!config.getBaseFile().exists())return null;if(config.getBaseFile().length()>65536)throw new IOException("AI 配置文件异常");
    JSONObject envelope=AiClient.json(new String(config.readFully(),StandardCharsets.UTF_8));if(envelope.getInt("version")!=1)throw new IOException("AI 配置版本未知");
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,android.util.Base64.decode(envelope.getString("iv"),android.util.Base64.NO_WRAP)));
    return AiClient.Config.from(AiClient.json(new String(cipher.doFinal(android.util.Base64.decode(envelope.getString("data"),android.util.Base64.NO_WRAP)),StandardCharsets.UTF_8)));
  }
  public synchronized long revision(){return revision;}
  public synchronized void forget(){revision++;config.delete();results.delete();}
  public synchronized JSONObject loadResults()throws Exception{if(!results.getBaseFile().exists())return new JSONObject();if(results.getBaseFile().length()>6*1024*1024)throw new IOException("AI 结果过大");return new JSONObject(new String(results.readFully(),StandardCharsets.UTF_8));}
  public synchronized void saveResults(JSONObject value,long expectedRevision)throws Exception{if(expectedRevision!=revision)return;byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>6*1024*1024)throw new IOException("AI 结果超过本地保存上限");write(results,bytes);}
}
