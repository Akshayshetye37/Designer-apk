package com.aurelia.pms;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
    private static final int FILE_PICKER = 4182, CREATE_EXPORT_FILE = 4183;
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
        webView.setWebViewClient(new WebViewClient());
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
    }

    private class NativeBridge {
        @JavascriptInterface public void postMessage(String raw) {
            try {
                JSONObject q = new JSONObject(raw);
                String type = q.optString("type");
                if ("recognizeId".equals(type)) recognize(q);
                else if ("printInvoice".equals(type)) runOnUiThread(() -> printHtml(q.optString("html", "")));
                else if ("shareText".equals(type)) { String title=q.optString("title","Share"); String text=q.optString("text",""); runOnUiThread(() -> shareText(title,text)); }
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
        String text=result.getText()==null?"":result.getText(), name="", id="";
        String excluded="(?i).*(GOVERNMENT|REPUBLIC|AUTHORITY|IDENTITY|NATIONAL|PASSPORT|DRIVING|LICEN[CS]E|DATE OF BIRTH|DOB|ADDRESS|INDIA|INDIAN).*";
        for(String line:text.split("\\R")) {
            String t=line.trim();
            if(name.isEmpty()&&t.matches("(?i)^(full\\s*name|name|surname|given names?)\\s*[:.-]?\\s*.+"))
                name=t.replaceFirst("(?i)^(full\\s*name|name|surname|given names?)\\s*[:.-]?\\s*","").trim();
            if(id.isEmpty()&&!t.matches(excluded)&&t.matches("(?i).*(?:[A-Z]{1,3}[- ]?\\d[A-Z0-9/-]{4,}|\\b\\d{6,}\\b).*")) {
                Matcher m=Pattern.compile("(?i)[A-Z0-9/-]{5,}").matcher(t);
                while(m.find()){String c=m.group();if(c.matches(".*\\d.*")){id=c;break;}}
            }
        }
        if(name.isEmpty()) for(String line:text.split("\\R")) {
            String t=line.trim();
            if(t.matches("[A-Z][A-Z ,.'-]{4,}")&&!t.matches(excluded)&&!t.matches(".*\\d.*")){name=t;break;}
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

    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){recognizer.close();if(webView!=null)webView.destroy();super.onDestroy();}
}
