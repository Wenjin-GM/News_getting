package cn.yilan.news;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
/** Read-only grant for exactly our own installed APK, never a caller-supplied file path. */
public final class ApkProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
  private File file(Uri uri)throws FileNotFoundException{if(getContext()==null||!"content".equals(uri.getScheme())||!(getContext().getPackageName()+".apk").equals(uri.getAuthority())||!"/release.apk".equals(uri.getPath())||uri.getQuery()!=null||uri.getFragment()!=null)throw new FileNotFoundException("Unknown package resource");return new File(getContext().getApplicationInfo().sourceDir);}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
  @Override public String getType(Uri uri){try{file(uri);return "application/vnd.android.package-archive";}catch(FileNotFoundException e){return null;}}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File f=file(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{"yilan-standalone-3.0.0.apk",f.length()});return c;}catch(FileNotFoundException e){return null;}}
  @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
  @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("Read only");}
  @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("Read only");}
}
