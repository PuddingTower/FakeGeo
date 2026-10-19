package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.security.MessageDigest;

public final class MapSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(UiKit.BACKGROUND);
        page.addView(UiKit.toolbar(this,"高德地图",this::finish));
        ScrollView scroll=new ScrollView(this); LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24),dp(20),dp(24),dp(32)); scroll.addView(body); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(page); UiKit.insetPage(page);
        add(body,"连接地图与地址搜索",24,UiKit.INK,true);
        add(body,"申请 Android SDK 类型的高德 Key，绑定下面的包名和签名。保存后返回首页即可加载地图。",14,UiKit.MUTED,false);
        add(body,"Android SDK Key",13,UiKit.MUTED,true);
        EditText key=new EditText(this); key.setSingleLine(true); key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); key.setHint("粘贴你的高德 Key"); key.setText(MapPreferences.key(this)); key.setTextColor(UiKit.INK); key.setHintTextColor(UiKit.MUTED); key.setPadding(dp(14),dp(14),dp(14),dp(14)); key.setBackground(UiKit.surface(this,14)); body.addView(key,new LinearLayout.LayoutParams(-1,dp(56)));
        android.widget.Button save=UiKit.button(this,getIntent().getBooleanExtra("from_setup",false)?"保存并返回引导":"保存并返回地图",true); LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,dp(54)); button.topMargin=dp(20); body.addView(save,button);
        save.setOnClickListener(v->{ String value=key.getText().toString().trim(); if(!value.isEmpty() && !value.matches("[a-zA-Z0-9_-]{16,128}")) { key.setError("请填写完整的高德 Key"); return; } MapPreferences.save(this,value); if (getIntent().getBooleanExtra("from_setup", false)) { finish(); return; } startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); });
        String identity="包名："+getPackageName()+"\nSHA1："+sha1();
        add(body,"应用绑定信息",18,UiKit.INK,true); TextView info=add(body,identity,13,UiKit.MUTED,false); info.setTextIsSelectable(true);
        android.widget.Button copy=UiKit.button(this,"复制包名与 SHA1",false); body.addView(copy,button); copy.setOnClickListener(v->{ ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("应用绑定信息",identity)); Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show(); });
        android.widget.Button console=UiKit.button(this,"打开高德开发者控制台",false); body.addView(console,button); console.setOnClickListener(v->open("https://console.amap.com/dev/key/app"));
        add(body,"高德服务",18,UiKit.INK,true); add(body,"地图展示、地点搜索和地址解析由高德提供。搜索关键词及地图交互信息会发送给高德。",14,UiKit.MUTED,false);
        android.widget.Button privacy=UiKit.button(this,"查看高德隐私政策",false); body.addView(privacy,button); privacy.setOnClickListener(v->open("https://lbs.amap.com/pages/privacy/"));
        android.widget.Button agree=UiKit.button(this,"同意并启用高德服务",false); body.addView(agree,button);
        agree.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("使用高德地图与搜索")
                .setMessage("地图、地址搜索和解析由高德提供，相关交互与搜索信息会发送给高德。请阅读高德隐私政策并同意后继续。")
                .setPositiveButton("同意并启用",(dialog,which)->{ MapPreferences.consent(this,true); Toast.makeText(this,"已同意高德服务",Toast.LENGTH_SHORT).show(); })
                .setNeutralButton("隐私政策",(dialog,which)->open("https://lbs.amap.com/pages/privacy/"))
                .setNegativeButton("暂不使用",null).show());
        android.widget.Button withdraw=UiKit.button(this,"撤回高德服务同意",false); body.addView(withdraw,button); withdraw.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("撤回高德服务同意")
                .setMessage("地图与搜索将暂停使用。已发布的虚拟定位配置会保留。")
                .setPositiveButton("撤回",(dialog,which)->{ MapPreferences.consent(this,false); finish(); }).setNegativeButton("取消",null).show());
    }
    private String sha1() {
        try {
            android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] hash=MessageDigest.getInstance("SHA-1").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
            StringBuilder out=new StringBuilder(); for(byte value:hash) { if(out.length()>0) out.append(':'); out.append(String.format(java.util.Locale.US,"%02X",value&255)); } return out.toString();
        } catch(Exception failure) { return "读取失败，请使用 keytool 获取"; }
    }
    private TextView add(LinearLayout body,String value,int size,int color,boolean bold) { TextView view=UiKit.text(this,value,size,color,bold); view.setPadding(0,dp(12),0,dp(12)); body.addView(view); return view; }
    private int dp(int value) { return UiKit.dp(this,value); }
    private void open(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); } catch(android.content.ActivityNotFoundException absent) { Toast.makeText(this,"未安装浏览器",Toast.LENGTH_SHORT).show(); } }
}
