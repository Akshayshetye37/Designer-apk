package com.aurelia.pms;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.content.SharedPreferences;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int FILE_PICKER = 4182, CREATE_EXPORT_FILE = 4183, BACKUP_FOLDER_PICKER = 4184;
    private String pendingBackupRequestId;
    private Uri backupTreeUri;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingExportBase64;
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view,String url) {
                super.onPageFinished(view,url);
                sendBackupFolderResult("status",backupTreeUri!=null,backupTreeUri==null?"":"Selected folder");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_PICKER); }
                catch (Exception e) { fileCallback = null; cb.onReceiveValue(null); return false; }
                return true;
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "AureliaNative");
        webView.loadUrl("file:///android_asset/index.html");
        String savedTree=getSharedPreferences("aurelia_backup",MODE_PRIVATE).getString("tree_uri",null);
        if(savedTree!=null){try{backupTreeUri=Uri.parse(savedTree);getContentResolver().takePersistableUriPermission(backupTreeUri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}}
    }

    private class NativeBridge {
        @JavascriptInterface public void postMessage(String raw) {
            try {
                JSONObject q = new JSONObject(raw);
                String type = q.optString("type");
                if ("recognizeId".equals(type)) recognize(q);
                else if ("chooseDeviceBackupFolder".equals(type)) {
                    String req=q.optString("requestId","");
                    runOnUiThread(() -> chooseBackupFolder(req));
                }
                else if ("backupDeviceNow".equals(type)) {
                    String req=q.optString("requestId","");
                    String name=q.optString("name","aurelia-backup.json");
                    String payload=q.optString("payload","");
                    runOnUiThread(() -> saveDeviceBackup(req,name,payload));
                }
                else if ("deviceBackupStatus".equals(type)) {
                    String req=q.optString("requestId","");
                    runOnUiThread(() -> sendBackupFolderResult(req,backupTreeUri!=null,backupTreeUri==null?"":"Selected folder"));
                }
                else if ("printInvoice".equals(type)) runOnUiThread(() -> printHtml(q.optString("html", "")));
                else if ("shareText".equals(type)) { String title=q.optString("title","Share"); String text=q.optString("text",""); runOnUiThread(() -> shareText(title,text)); }
                else if ("shareImage".equals(type)) { String name=q.optString("name","invoice.png"); String data=q.optString("data",""); runOnUiThread(() -> shareImage(name,data)); }
                else if ("openUrl".equals(type)) { String url=q.optString("url",""); runOnUiThread(() -> openExternalUrl(url)); }
                else if ("saveFile".equals(type)) {
                    String name=q.optString("name","aurelia-export.bin"), mime=q.optString("mime","application/octet-stream");
                    String data=q.optString("data","");
                    runOnUiThread(() -> requestSaveFile(name,mime,data));
                }
            } catch (Exception e) { Log.e("AureliaPMS", "Native bridge request failed", e); }
        }
    }

    private void recognize(JSONObject q) throws Exception {
        String id=q.optString("requestId");
        byte[] data=Base64.decode(q.optString("data"),Base64.DEFAULT);
        Bitmap bmp=BitmapFactory.decodeByteArray(data,0,data.length);
        if (bmp==null) { send(id,new JSONObject().put("text","")); return; }
        recognizer.process(InputImage.fromBitmap(bmp,0))
            .addOnSuccessListener(t->{try{send(id,parse(t));}catch(Exception e){send(id,emptyPayload(t.getText()));}finally{bmp.recycle();}})
            .addOnFailureListener(e->{send(id,emptyPayload(""));bmp.recycle();});
    }

    private JSONObject parse(Text result) throws Exception {
        String text=result.getText()==null?"":result.getText();
        String name="",id="";
        String excluded="(?i).*(GOVERNMENT|REPUBLIC|AUTHORITY|IDENTITY|NATIONAL|PASSPORT|DRIVING|LICEN[CS]E|DATE OF BIRTH|\\bDOB\\b|ADDRESS|INDIA|INDIAN|SIGNATURE|MALE|FEMALE|NRC CARD|REGISTRATION CARD).*";
        String[] lines=text.split("\\R");
        for(String line:lines) {
            String t=line.trim().replaceAll("\\s+"," ");
            if(name.isEmpty()&&!t.matches(excluded)) {
                Matcher label=Pattern.compile("(?i)^(?:full\\s*name|name\\s*of\\s*holder|name|surname|family\\s*name|given\\s*names?|forenames?)\\s*[:#.-]?\\s*(.+)$").matcher(t);
                if(label.find()) {
                    String candidate=label.group(1).replaceAll("[^\\p{L} .’'-]"," ").replaceAll("\\s+"," ").trim();
                    if(candidate.length()>=3&&!candidate.matches(".*\\d.*"))name=candidate;
                }
            }
            if(id.isEmpty()&&!t.matches(excluded)&&!t.matches("(?i).*(DATE OF BIRTH|\\bDOB\\b|PHONE|MOBILE|POSTAL|PIN CODE|ADDRESS).*")) {
                Matcher labeled=Pattern.compile("(?i)(?:NRC|ID|IDENTITY|DOCUMENT|PASSPORT|CARD)\\s*(?:NO|NUMBER|#)?\\s*[:#.-]?\\s*([A-Z0-9][A-Z0-9/-]{4,})").matcher(t);
                if(labeled.find())id=labeled.group(1);
                if(id.isEmpty()) {
                    Matcher m=Pattern.compile("(?i)\\b(?:[A-Z]{1,3}[-/]?\\d[A-Z0-9/-]{4,}|\\d{6,14})\\b").matcher(t);
                    if(m.find())id=m.group();
                }
            }
        }
        if(name.isEmpty()) {
            for(String line:lines) {
                String t=line.trim().replaceAll("\\s+"," ");
                if(t.length()<5||t.length()>60||t.matches(excluded)||t.matches(".*\\d.*"))continue;
                if(t.matches("(?i)^[\\p{L}][\\p{L} .’'-]+$")&&t.split("\\s+").length>=2) {name=t;break;}
                if(t.matches("[A-Z][A-Z ,.'-]{4,}")) {name=t;break;}
            }
        }
        return new JSONObject().put("fullName",name).put("idNumber",id).put("text",text);
    }

    private JSONObject emptyPayload(String text) {
        JSONObject o=new JSONObject();try{o.put("text",text==null?"":text);}catch(Exception ignored){}return o;
    }

    private void send(String id,JSONObject payload) {
        String js="window.aureliaNativeOcrResult&&window.aureliaNativeOcrResult("+JSONObject.quote(id)+","+JSONObject.quote(payload.toString())+");";
        runOnUiThread(()->webView.evaluateJavascript(js,null));
    }

    private void chooseBackupFolder(String requestId) {
        try {
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pendingBackupRequestId=requestId;
            startActivityForResult(i,BACKUP_FOLDER_PICKER);
        } catch(Exception e){Log.e("AureliaPMS","Folder picker failed",e);sendBackupFolderResult(requestId,false,"Could not open folder picker");}
    }
    private void sendBackupFolderResult(String requestId,boolean ok,String label) {
        String js="window.aureliaNativeBackupFolderResult&&window.aureliaNativeBackupFolderResult("+JSONObject.quote(requestId)+","+ok+","+JSONObject.quote(label==null?"":label)+");";
        if(webView!=null)webView.post(()->webView.evaluateJavascript(js,null));
    }
    private void saveDeviceBackup(String requestId,String name,String payload) {
        if(backupTreeUri==null){sendBackupResult(requestId,false,"Choose a backup folder first");return;}
        try {
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(backupTreeUri,DocumentsContract.getTreeDocumentId(backupTreeUri));
            Uri fileUri=DocumentsContract.createDocument(getContentResolver(),parent,"application/json",name);
            if(fileUri==null)throw new IllegalStateException("Could not create backup file");
            try(OutputStream out=getContentResolver().openOutputStream(fileUri,"wt")) {
                if(out==null)throw new IllegalStateException("Could not open backup file");
                out.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
            }
            sendBackupResult(requestId,true,"");
        } catch(Exception e){Log.e("AureliaPMS","Backup write failed",e);sendBackupResult(requestId,false,e.getMessage()==null?"Folder permission or storage error":e.getMessage());}
    }
    private void sendBackupResult(String requestId,boolean ok,String error) {
        String js="window.aureliaNativeDeviceBackupResult&&window.aureliaNativeDeviceBackupResult("+JSONObject.quote(requestId)+","+ok+","+JSONObject.quote(error==null?"":error)+");";
        if(webView!=null)webView.post(()->webView.evaluateJavascript(js,null));
    }

    private void shareImage(String name,String base64) {
        try {
            byte[] bytes=Base64.decode(base64,Base64.DEFAULT);
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if(bitmap==null){toastJs("Could not create invoice image.");return;}
            String safeName=(name==null?"invoice.png":name).replaceAll("[^A-Za-z0-9._-]","_");
            String uriString=MediaStore.Images.Media.insertImage(getContentResolver(),bitmap,safeName,"Aurelia PMS invoice");
            bitmap.recycle();
            if(uriString==null){toastJs("Could not prepare PNG for sharing.");return;}
            Uri uri=Uri.parse(uriString);
            Intent intent=new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM,uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,"Share invoice PNG"));
        } catch(Exception e){Log.e("AureliaPMS","Image share failed",e);toastJs("Could not open image sharing.");}
    }

    private void shareText(String title,String text) {
        try { Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_SUBJECT,title); i.putExtra(Intent.EXTRA_TEXT,text); startActivity(Intent.createChooser(i,"Share using")); }
        catch(Exception e){ toastJs("No compatible sharing app was found."); }
    }
    private void openExternalUrl(String url) {
        try { if(url==null || !(url.startsWith("https://") || url.startsWith("http://"))) { toastJs("Invalid link."); return; } startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch(ActivityNotFoundException e){ toastJs("No app or browser can open this link."); }
        catch(Exception e){ toastJs("Could not open the link."); }
    }
    private void requestSaveFile(String name,String mime,String base64) {
        try {
            pendingExportBase64=base64;
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE,name);
            startActivityForResult(intent,CREATE_EXPORT_FILE);
        } catch(Exception e) { pendingExportBase64=null; toastJs("Could not open the Android save dialog."); }
    }

    private void printHtml(String html) {
        try {
            WebView printView=new WebView(this);
            printView.getSettings().setJavaScriptEnabled(false);
            printView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view,String url) {
                    try {
                        PrintManager manager=(PrintManager)getSystemService(Context.PRINT_SERVICE);
                        PrintDocumentAdapter adapter=view.createPrintDocumentAdapter("Aurelia-Bill");
                        manager.print("Aurelia bill",adapter,new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(new PrintAttributes.Resolution("aurelia","Aurelia",300,300))
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build());
                    } catch(Exception e) { Log.e("AureliaPMS","Print setup failed",e);toastJs("Could not open Android print dialog."); }
                }
            });
            String page="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><style>body{font-family:Arial,sans-serif;color:#222;margin:18px}.invoice{background:#fff;color:#222;width:100%;box-sizing:border-box}.noprint,button{display:none!important}img{max-width:100%}table{width:100%;border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #ccc;text-align:left}@page{size:A4;margin:12mm}</style></head><body>"+html+"</body></html>";
            printView.loadDataWithBaseURL("https://aurelia.local/",page,"text/html","UTF-8",null);
        } catch(Exception e) { Log.e("AureliaPMS","Print failed",e);toastJs("Could not prepare bill for printing."); }
    }

    private void toastJs(String message) {
        if(webView!=null)webView.post(()->webView.evaluateJavascript("window.toast&&window.toast("+JSONObject.quote(message)+")",null));
    }

    @Override protected void onActivityResult(int r,int c,Intent d) {
        super.onActivityResult(r,c,d);
        if(r==FILE_PICKER&&fileCallback!=null){fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(c,d));fileCallback=null;}
        else if(r==BACKUP_FOLDER_PICKER) {
            String req=pendingBackupRequestId; pendingBackupRequestId=null;
            if(c==RESULT_OK&&d!=null&&d.getData()!=null) {
                try {
                    Uri uri=d.getData();
                    int flags=d.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri,flags);
                    backupTreeUri=uri;
                    getSharedPreferences("aurelia_backup",MODE_PRIVATE).edit().putString("tree_uri",uri.toString()).apply();
                    String label=uri.getLastPathSegment()==null?"Selected folder":uri.getLastPathSegment().replaceAll(".*:","");
                    sendBackupFolderResult(req,true,label);
                } catch(Exception e){Log.e("AureliaPMS","Persist folder permission failed",e);sendBackupFolderResult(req,false,"Could not keep folder permission");}
            } else sendBackupFolderResult(req,backupTreeUri!=null,backupTreeUri==null?"Folder selection cancelled":"");
        }
        else if(r==CREATE_EXPORT_FILE) {
            String data=pendingExportBase64;pendingExportBase64=null;
            if(c==RESULT_OK&&d!=null&&d.getData()!=null&&data!=null) {
                try(OutputStream out=getContentResolver().openOutputStream(d.getData())) {
                    if(out!=null)out.write(Base64.decode(data,Base64.DEFAULT));
                    toastJs("File saved successfully.");
                } catch(Exception e){Log.e("AureliaPMS","File export failed",e);toastJs("Could not save file.");}
            }
        }
    }

    @Override public void onBackPressed(){
        if(webView==null){super.onBackPressed();return;}
        webView.evaluateJavascript("(function(){var s=document.getElementById('sh');if(s&&s.classList.contains('show')){closeS();return 'handled';}return 'unhandled';})()", value -> {
            if("\"handled\"".equals(value)) return;
            if(webView!=null&&webView.canGoBack()) webView.goBack(); else MainActivity.super.onBackPressed();
        });
    }
    @Override protected void onDestroy(){recognizer.close();if(webView!=null)webView.destroy();super.onDestroy();}
}
