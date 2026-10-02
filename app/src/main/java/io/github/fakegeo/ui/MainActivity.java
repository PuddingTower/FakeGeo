package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItemV2;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;
import com.amap.api.services.poisearch.PoiSearchV2;
import com.amap.api.services.poisearch.PoiResultV2;
import com.amap.api.services.poisearch.VisualSearchResult;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.Gcj02;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Map and deliberate publication only. Hook parameters and diagnostics live in settings. */
public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout screen,mapHost;
    private LinearLayout top,bottom,resultRows,empty;
    private ScrollView results;
    private TextView title,address,coordinates,status,emptyTitle,emptyDescription;
    private EditText search;
    private Button action,stop,configure;
    private View settings,pin,recenter,movement;
    private Button realPosition;
    private io.github.fakegeo.location.RealPositionFinder realFinder;
    private MapView mapView;
    private AMap map;
    private String loadedKey="",selectedTitle="",selectedAddress="";
    private double latitude,longitude;
    private long localRevision;
    private boolean hasPoint,busy,resumed,consentShown,cameraSelection;
    private int searchGeneration,geocodeGeneration;
    private Bundle initialState;
    public static boolean moduleActive() { return false; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SetupPreferences.complete(this)) { startActivity(new Intent(this, OnboardingActivity.class)); finish(); return; }
        initialState=state;
        Profile p=ConfigStore.load(this); localRevision=p.revision;
        double[] draft=MapPreferences.draft(this,p.revision);
        latitude=draft==null?p.latitude:draft[0]; longitude=draft==null?p.longitude:draft[1];
        hasPoint=latitude!=0 || longitude!=0; selectedTitle=MapPreferences.label(this,latitude,longitude);
        if(state!=null) { latitude=state.getDouble("selected_lat",latitude); longitude=state.getDouble("selected_lon",longitude); hasPoint=state.getBoolean("has_point",hasPoint); selectedTitle=state.getString("selected_title",selectedTitle); selectedAddress=state.getString("selected_address",""); }
        buildScreen(); refreshCard();
    }
    private void buildScreen() {
        screen=new FrameLayout(this); screen.setBackgroundColor(0xFFEAF0ED); setContentView(screen);
        mapHost=new FrameLayout(this); screen.addView(mapHost,new FrameLayout.LayoutParams(-1,-1));
        empty=new LinearLayout(this); empty.setOrientation(LinearLayout.VERTICAL); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(32),0,dp(32),dp(100));
        View symbol=UiKit.icon(this,UiKit.PIN); empty.addView(symbol,new LinearLayout.LayoutParams(dp(44),dp(54)));
        emptyTitle=UiKit.text(this,"连接你的地图",22,UiKit.INK,true); emptyTitle.setGravity(Gravity.CENTER); LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2); space.topMargin=dp(18); empty.addView(emptyTitle,space);
        emptyDescription=UiKit.text(this,"配置高德 Key 后，即可搜索地址和拖动地图选点。",14,UiKit.MUTED,false); emptyDescription.setGravity(Gravity.CENTER); emptyDescription.setLineSpacing(dp(5),1); empty.addView(emptyDescription,space);
        configure=UiKit.button(this,"配置高德地图",false); LinearLayout.LayoutParams setup=new LinearLayout.LayoutParams(dp(176),dp(48)); setup.topMargin=dp(24); empty.addView(configure,setup); configure.setOnClickListener(v->startActivity(new Intent(this,MapSettingsActivity.class)));
        mapHost.addView(empty,new FrameLayout.LayoutParams(-1,-1));

        top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setClipChildren(false); top.setClipToPadding(false);
        LinearLayout searchBox=new LinearLayout(this); searchBox.setGravity(Gravity.CENTER_VERTICAL); searchBox.setPadding(dp(16),0,dp(4),0); searchBox.setBackground(UiKit.rounded(Color.WHITE,18)); searchBox.setElevation(0);
        search=new EditText(this); search.setHint("搜索地址、地标或建筑"); search.setTextSize(15); search.setTextColor(UiKit.INK); search.setHintTextColor(UiKit.MUTED); search.setSingleLine(true); search.setBackgroundColor(Color.TRANSPARENT); search.setPadding(0,0,dp(4),0); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH); search.setInputType(android.text.InputType.TYPE_CLASS_TEXT); searchBox.addView(search,new LinearLayout.LayoutParams(0,-1,1));
        View submit=UiKit.icon(this,UiKit.SEARCH); submit.setPadding(dp(13),dp(13),dp(13),dp(13)); submit.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x20087F73),UiKit.rounded(0xFFE8F3F0,14),null)); submit.setContentDescription("搜索地址"); searchBox.addView(submit,new LinearLayout.LayoutParams(dp(48),dp(48))); submit.setOnClickListener(v->search());
        search.setOnEditorActionListener((view,id,event)-> { if(id==EditorInfo.IME_ACTION_SEARCH || event!=null && event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER && event.getAction()==android.view.KeyEvent.ACTION_UP) { search(); return true; } return false; });
        top.addView(searchBox,new LinearLayout.LayoutParams(0,-1,1));
        settings=UiKit.icon(this,UiKit.SETTINGS); settings.setPadding(dp(13),dp(13),dp(13),dp(13)); settings.setBackground(UiKit.rounded(Color.WHITE,18)); settings.setElevation(0); settings.setContentDescription("高级设置"); LinearLayout.LayoutParams gear=new LinearLayout.LayoutParams(dp(56),-1); gear.leftMargin=dp(10); top.addView(settings,gear); settings.setOnClickListener(v->startActivity(new Intent(this,AdvancedSettingsActivity.class)));
        FrameLayout.LayoutParams header=new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP); header.leftMargin=dp(20); header.rightMargin=dp(20); header.topMargin=dp(20); screen.addView(top,header);

        results=new ScrollView(this); results.setBackground(UiKit.rounded(Color.WHITE,18)); results.setElevation(dp(8)); results.setVisibility(View.GONE); results.setFillViewport(false); resultRows=new LinearLayout(this); resultRows.setOrientation(LinearLayout.VERTICAL); results.addView(resultRows);
        FrameLayout.LayoutParams list=new FrameLayout.LayoutParams(-1,dp(272),Gravity.TOP); list.leftMargin=dp(20); list.rightMargin=dp(20); list.topMargin=dp(88); screen.addView(results,list);

        pin=UiKit.icon(this,UiKit.PIN); pin.setVisibility(View.GONE); FrameLayout.LayoutParams marker=new FrameLayout.LayoutParams(dp(36),dp(44),Gravity.CENTER); screen.addView(pin,marker); pin.setTranslationY(-dp(16)); pin.setContentDescription("地图中心选点");
        recenter=UiKit.icon(this,UiKit.TARGET); recenter.setPadding(dp(12),dp(12),dp(12),dp(12)); recenter.setBackground(UiKit.rounded(Color.WHITE,15)); recenter.setElevation(dp(5)); recenter.setVisibility(View.GONE); recenter.setContentDescription("回到已发布位置"); FrameLayout.LayoutParams target=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END|Gravity.BOTTOM); target.rightMargin=dp(20); target.bottomMargin=dp(262); screen.addView(recenter,target); recenter.setOnClickListener(v->{ Profile p=ConfigStore.published(this); if(p.latitude!=0 || p.longitude!=0) { geocodeGeneration++; latitude=p.latitude; longitude=p.longitude; hasPoint=true; selectedTitle=MapPreferences.label(this,latitude,longitude); selectedAddress=""; MapPreferences.draft(this,localRevision,latitude,longitude); refreshCard(); moveToWgs(p.latitude,p.longitude); } });

        movement=UiKit.button(this,"悬浮摇杆",false); movement.setElevation(dp(5));
        FrameLayout.LayoutParams moveButton=new FrameLayout.LayoutParams(dp(128),dp(48),Gravity.START|Gravity.BOTTOM);moveButton.leftMargin=dp(20);moveButton.bottomMargin=dp(262);screen.addView(movement,moveButton);
        movement.setOnClickListener(v->startActivity(new Intent(this,MovementSettingsActivity.class)));
        realPosition=UiKit.button(this,"真实位置",false);realPosition.setElevation(dp(5));realPosition.setContentDescription("将地图准星移到当前真实位置");
        FrameLayout.LayoutParams realButton=new FrameLayout.LayoutParams(dp(112),dp(48),Gravity.END|Gravity.BOTTOM);realButton.rightMargin=dp(20);realButton.bottomMargin=dp(262);screen.addView(realPosition,realButton);
        realPosition.setOnClickListener(v->findRealPosition());
        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(22),dp(18),dp(22),dp(20)); bottom.setBackground(UiKit.rounded(Color.WHITE,24)); bottom.setElevation(dp(12));
        status=UiKit.text(this,"虚拟定位未开启",12,UiKit.ACCENT,true); bottom.addView(status);
        title=UiKit.text(this,"选择你想去的位置",20,UiKit.INK,true); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); LinearLayout.LayoutParams titleLayout=new LinearLayout.LayoutParams(-1,-2); titleLayout.topMargin=dp(10); bottom.addView(title,titleLayout);
        address=UiKit.text(this,"搜索地址，或拖动地图设置选点",13,UiKit.MUTED,false); address.setMaxLines(2); address.setEllipsize(android.text.TextUtils.TruncateAt.END); LinearLayout.LayoutParams info=new LinearLayout.LayoutParams(-1,-2); info.topMargin=dp(6); bottom.addView(address,info);
        coordinates=UiKit.text(this,"—",12,UiKit.MUTED,false); coordinates.setTypeface(android.graphics.Typeface.MONOSPACE); bottom.addView(coordinates,info);
        LinearLayout buttons=new LinearLayout(this); buttons.setGravity(Gravity.CENTER_VERTICAL); action=UiKit.button(this,"开启虚拟定位",true); action.setOnClickListener(v->primaryAction()); buttons.addView(action,new LinearLayout.LayoutParams(0,dp(54),1));
        stop=UiKit.button(this,"停止",false); stop.setOnClickListener(v->publish(false)); LinearLayout.LayoutParams stopLayout=new LinearLayout.LayoutParams(dp(76),dp(54)); stopLayout.leftMargin=dp(10); buttons.addView(stop,stopLayout); stop.setVisibility(View.GONE);
        LinearLayout.LayoutParams buttonLayout=new LinearLayout.LayoutParams(-1,-2); buttonLayout.topMargin=dp(18); bottom.addView(buttons,buttonLayout);
        FrameLayout.LayoutParams card=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); card.leftMargin=dp(16); card.rightMargin=dp(16); card.bottomMargin=dp(16); screen.addView(bottom,card);
        screen.setOnApplyWindowInsetsListener((view,insets)-> {
            int up,down;
            if(android.os.Build.VERSION.SDK_INT>=30) { android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); up=bars.top; down=Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom); }
            else { up=insets.getSystemWindowInsetTop(); down=insets.getSystemWindowInsetBottom(); }
            ((FrameLayout.LayoutParams)top.getLayoutParams()).topMargin=up+dp(14); top.requestLayout();
            ((FrameLayout.LayoutParams)results.getLayoutParams()).topMargin=up+dp(82); results.requestLayout();
            ((FrameLayout.LayoutParams)bottom.getLayoutParams()).bottomMargin=down+dp(12); bottom.requestLayout();
            ((FrameLayout.LayoutParams)recenter.getLayoutParams()).bottomMargin=down+dp(318); recenter.requestLayout();
            ((FrameLayout.LayoutParams)realPosition.getLayoutParams()).bottomMargin=down+dp(258);realPosition.requestLayout();
            ((FrameLayout.LayoutParams)movement.getLayoutParams()).bottomMargin=down+dp(258);movement.requestLayout();return insets;
        }); screen.requestApplyInsets();
    }
    @Override protected void onResume() {
        super.onResume(); if (screen == null) return; resumed=true;
        Profile p=ConfigStore.load(this); if(p.revision!=localRevision) { geocodeGeneration++; localRevision=p.revision; latitude=p.latitude; longitude=p.longitude; hasPoint=latitude!=0 || longitude!=0; selectedTitle=MapPreferences.label(this,latitude,longitude); selectedAddress=""; if(map!=null && hasPoint) moveToWgs(latitude,longitude); }
        refreshCard(); MapView previousMap=mapView; ensureMap(); if(mapView!=null && mapView==previousMap) mapView.onResume();
    }
    private void ensureMap() {
        String key=MapPreferences.key(this);
        if(mapView!=null && (!loadedKey.equals(key) || !MapPreferences.consent(this))) {
            mapView.onPause(); mapView.onDestroy(); mapHost.removeView(mapView); mapView=null; map=null; loadedKey=""; pin.setVisibility(View.GONE); recenter.setVisibility(View.GONE); results.setVisibility(View.GONE); searchGeneration++; geocodeGeneration++;
            if(!MapPreferences.consent(this)) { MapsInitializer.updatePrivacyAgree(this,false); ServiceSettings.updatePrivacyAgree(this,false); }
        }
        if(key.isEmpty()) {
            empty.setVisibility(View.VISIBLE); search.setEnabled(false); emptyTitle.setText("连接你的地图"); emptyDescription.setText("配置高德 Key 后，即可搜索地址和拖动地图选点。"); configure.setText("配置高德地图"); configure.setOnClickListener(v->startActivity(new Intent(this,MapSettingsActivity.class))); return;
        }
        if(!MapPreferences.consent(this)) {
            empty.setVisibility(View.VISIBLE); search.setEnabled(false); emptyTitle.setText("启用高德地图"); emptyDescription.setText("同意高德服务后，可以查看地图、搜索地点与解析地址。"); configure.setText("启用地图与搜索"); configure.setOnClickListener(v->consent());
            if(!consentShown) consent(); return;
        }
        if(mapView!=null) return;
        try {
            MapsInitializer.updatePrivacyShow(this,true,true); MapsInitializer.updatePrivacyAgree(this,true); MapsInitializer.setApiKey(key);
            ServiceSettings.updatePrivacyShow(this,true,true); ServiceSettings.updatePrivacyAgree(this,true); ServiceSettings.getInstance().setApiKey(key);
            MapView view=new MapView(this); view.onCreate(initialState); initialState=null; mapView=view; mapHost.addView(view,0,new FrameLayout.LayoutParams(-1,-1)); map=view.getMap(); loadedKey=key;
            map.getUiSettings().setZoomControlsEnabled(false); map.getUiSettings().setRotateGesturesEnabled(false); map.getUiSettings().setTiltGesturesEnabled(false); map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setLogoBottomMargin(dp(248));
            double[] point=hasPoint?Gcj02.fromWgs84(latitude,longitude):new double[]{39.9092,116.3975}; map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(point[0],point[1]),16));
            map.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
                @Override public void onCameraChange(CameraPosition position) {}
                @Override public void onCameraChangeFinish(CameraPosition position) { if(cameraSelection) { cameraSelection=false; choose(position.target,"",""); } }
            });
            map.setOnMapTouchListener(event->{ if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE) cameraSelection=true; });
            map.setOnMapClickListener(pointOnMap->{ cameraSelection=false; results.setVisibility(View.GONE); hideKeyboard(); choose(pointOnMap,"",""); map.animateCamera(CameraUpdateFactory.newLatLng(pointOnMap)); });
            empty.setVisibility(View.GONE); pin.setVisibility(View.VISIBLE); recenter.setVisibility(View.VISIBLE); search.setEnabled(true);
            if(resumed) mapView.onResume();
        } catch(Throwable failure) {
            if(mapView!=null) { try { mapView.onDestroy(); } catch(Throwable ignored) {} mapHost.removeView(mapView); }
            mapView=null; map=null; loadedKey=""; empty.setVisibility(View.VISIBLE); emptyTitle.setText("地图暂时无法加载"); emptyDescription.setText("请检查高德 Key 和网络连接后重试。");
            configure.setText("检查地图配置"); configure.setOnClickListener(v->startActivity(new Intent(this,MapSettingsActivity.class))); toast("地图初始化失败："+failure.getClass().getSimpleName());
        }
    }
    private void consent() {
        consentShown=true;
        new AlertDialog.Builder(this).setTitle("使用高德地图与搜索")
                .setMessage("地图、地点搜索和地址解析由高德提供，相关交互与搜索信息会发送给高德。请阅读并同意高德隐私政策后继续。")
                .setPositiveButton("同意并继续",(dialog,which)->{ MapPreferences.consent(this,true); ensureMap(); })
                .setNeutralButton("隐私政策",(dialog,which)->{ try { startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://lbs.amap.com/pages/privacy/"))); } catch(android.content.ActivityNotFoundException absent) { toast("未安装浏览器"); } })
                .setNegativeButton("暂不使用",null).show();
    }
    private void choose(LatLng point,String name,String detail) {
        double[] gps=Gcj02.toWgs84(point.latitude,point.longitude);
        boolean moved=!hasPoint || Math.abs(gps[0]-latitude)>1e-7 || Math.abs(gps[1]-longitude)>1e-7;
        if(moved) { latitude=gps[0]; longitude=gps[1]; hasPoint=true; selectedTitle=name; selectedAddress=detail; }
        else if(!name.isEmpty()) { selectedTitle=name; selectedAddress=detail; }
        MapPreferences.draft(this,localRevision,latitude,longitude); refreshCard();
        if(!name.isEmpty()) MapPreferences.label(this,latitude,longitude,name);
        else if(moved) reverseGeocode(point);
    }
    private void reverseGeocode(LatLng point) {
        int generation=++geocodeGeneration;
        try {
            GeocodeSearch geocoder=new GeocodeSearch(this);
            geocoder.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                @Override public void onGeocodeSearched(GeocodeResult result,int code) {}
                @Override public void onRegeocodeSearched(RegeocodeResult result,int code) {
                    if(isDestroyed() || generation!=geocodeGeneration) return;
                    if(code==1000 && result!=null && result.getRegeocodeAddress()!=null) {
                        selectedTitle="地图选点"; selectedAddress=result.getRegeocodeAddress().getFormatAddress(); MapPreferences.label(MainActivity.this,latitude,longitude,selectedAddress); refreshCard();
                    }
                }
            });
            geocoder.getFromLocationAsyn(new RegeocodeQuery(new LatLonPoint(point.latitude,point.longitude),200,GeocodeSearch.AMAP));
        } catch(Exception ignored) { /* Coordinates remain selectable if address lookup is unavailable. */ }
    }
    private void search() {
        String keyword=search.getText().toString().trim(); if(keyword.isEmpty()) { toast("请输入地址或地点名称"); return; }
        if(map==null) { startActivity(new Intent(this,MapSettingsActivity.class)); return; }
        hideKeyboard(); int generation=++searchGeneration; results.setVisibility(View.VISIBLE); resultRows.removeAllViews(); resultMessage("正在搜索…");
        try {
            PoiSearchV2.Query query=new PoiSearchV2.Query(keyword,"",""); query.setPageSize(12);
            PoiSearchV2 finder=new PoiSearchV2(this,query);
            finder.setOnPoiSearchListener(new PoiSearchV2.OnPoiSearchListener() {
                @Override public void onPoiItemSearched(PoiItemV2 item,int code) {}
                @Override public void onVisualSearched(VisualSearchResult result,int code) {}
                @Override public void onPoiSearched(PoiResultV2 result,int code) {
                    if(isDestroyed() || generation!=searchGeneration) return;
                    resultRows.removeAllViews();
                    if(code!=1000) { resultMessage(code==10001 || code==10007 || code==10008 || code==10009?"高德 Key 或签名校验失败，请在高级设置中检查。":"搜索暂时不可用，请检查网络后重试（"+code+"）。"); return; }
                    if(result==null || result.getPois()==null || result.getPois().isEmpty()) { resultMessage("没有找到地点，请补充城市、街道或建筑名称。"); return; }
                    for(PoiItemV2 item:result.getPois()) { if(item.getLatLonPoint()==null) continue;
                        LinearLayout row=new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(18),dp(14),dp(18),dp(14));
                        row.addView(UiKit.text(MainActivity.this,item.getTitle(),16,UiKit.INK,true));
                        String detail=(item.getCityName()==null?"":item.getCityName())+(item.getAdName()==null?"":item.getAdName())+(item.getSnippet()==null?"":item.getSnippet()); TextView desc=UiKit.text(MainActivity.this,detail,12,UiKit.MUTED,false); desc.setPadding(0,dp(5),0,0); row.addView(desc); resultRows.addView(row);
                        row.setOnClickListener(v->{ cameraSelection=false; geocodeGeneration++; results.setVisibility(View.GONE); LatLonPoint value=item.getLatLonPoint(); LatLng selected=new LatLng(value.getLatitude(),value.getLongitude()); choose(selected,item.getTitle(),detail); map.animateCamera(CameraUpdateFactory.newLatLngZoom(selected,17)); });
                        View line=new View(MainActivity.this); line.setBackgroundColor(0xFFEFF3F2); resultRows.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
                    }
                }
            }); finder.searchPOIAsyn();
        } catch(Exception failure) { resultRows.removeAllViews(); resultMessage("无法发起搜索，请检查高德服务设置。"); }
    }
    private void resultMessage(String value) { TextView text=UiKit.text(this,value,14,UiKit.MUTED,false); text.setPadding(dp(18),dp(24),dp(18),dp(24)); resultRows.addView(text); }
    private void refreshCard() {
        Profile published=ConfigStore.published(this); boolean changed=hasPoint && (Math.abs(published.latitude-latitude)>1e-7 || Math.abs(published.longitude-longitude)>1e-7);
        status.setText(published.enabled?(changed?"●  运行中 · 新位置待应用":"●  虚拟定位已开启"):"○  虚拟定位未开启");
        title.setText(hasPoint?(selectedTitle.isEmpty()?"地图选点":selectedTitle):"选择你想去的位置");
        address.setText(hasPoint?(selectedAddress.isEmpty()?"拖动地图调整，或搜索具体地址":selectedAddress):"搜索地址，或拖动地图设置选点");
        coordinates.setText(hasPoint?String.format(Locale.US,"%.6f, %.6f",latitude,longitude):"尚未选择位置");
        action.setText(busy?"正在应用…":published.enabled?(changed?"应用新位置":"停止虚拟定位"):"开启虚拟定位");
        action.setEnabled(!busy && (hasPoint || published.enabled)); action.setAlpha(action.isEnabled()?1:0.45f); settings.setEnabled(!busy);
        stop.setVisibility(published.enabled && changed?View.VISIBLE:View.GONE); stop.setEnabled(!busy);
    }
    private void primaryAction() {
        Profile p=ConfigStore.published(this); boolean changed=Math.abs(p.latitude-latitude)>1e-7 || Math.abs(p.longitude-longitude)>1e-7;
        publish(!p.enabled || changed);
    }
    private void publish(boolean enable) {
        if(busy) return;
        try {
            Profile previous=ConfigStore.load(this); JSONObject json=previous.json();
            json.put("enabled",enable).put("revision",Math.max(System.currentTimeMillis(),previous.revision+1));
            io.github.fakegeo.motion.MotionModel.clear(json);
            if(enable) json.put("latitude",latitude).put("longitude",longitude);
            Profile next=new Profile(json); busy=true; refreshCard();
            worker.execute(()-> {
                Exception error=null;
                try { io.github.fakegeo.root.RootProfilePublisher.publishUser(MainActivity.this,next); } catch(Exception failure) { error=failure; }
                Exception outcome=error;
                runOnUiThread(()-> { if(isDestroyed()) return; busy=false; if(outcome==null) { localRevision=ConfigStore.published(MainActivity.this).revision; stopService(new Intent(MainActivity.this,io.github.fakegeo.motion.JoystickService.class)); MapPreferences.draft(MainActivity.this,localRevision,latitude,longitude); MapPreferences.label(MainActivity.this,latitude,longitude,selectedAddress.isEmpty()?selectedTitle:selectedAddress); toast(enable?"虚拟定位配置已应用":"虚拟定位已停止"); }
                    else new AlertDialog.Builder(MainActivity.this).setTitle(enable?"暂时无法开启":"停止未完成").setMessage(outcome.getMessage()).setPositiveButton("知道了",null).setNeutralButton("高级设置",(dialog,which)->startActivity(new Intent(MainActivity.this,AdvancedSettingsActivity.class))).show(); refreshCard(); });
            });
        } catch(Exception failure) { busy=false; refreshCard(); toast(failure.getMessage()); }
    }
    private void moveToWgs(double lat,double lon) { if(map==null) return; cameraSelection=false; double[] point=Gcj02.fromWgs84(lat,lon); map.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(point[0],point[1]))); }
    private void findRealPosition() {
        if(realFinder!=null) return;
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION},36);return;
        }
        realPosition.setEnabled(false);realPosition.setText("定位中…");
        realFinder=new io.github.fakegeo.location.RealPositionFinder(this,new io.github.fakegeo.location.RealPositionFinder.Callback() {
            @Override public void found(double lat,double lon,float accuracy) {
                realFinder=null;if(isDestroyed()) return;realPosition.setEnabled(true);realPosition.setText("真实位置");
                if(!resumed) return;geocodeGeneration++;double[] point=Gcj02.fromWgs84(lat,lon);
                choose(new LatLng(point[0],point[1]),"当前真实位置",String.format(Locale.CHINA,"GPS 精度约 %.0f 米 · 尚未应用",accuracy));
                moveToWgs(lat,lon);toast("地图已回到真实位置，虚拟定位配置未改变");
            }
            @Override public void failed(String message) { realFinder=null;if(isDestroyed()) return;realPosition.setEnabled(true);realPosition.setText("真实位置");if(resumed) toast(message); }
        });
        realFinder.start();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==36) { if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED) findRealPosition();else toast("获取真实位置需要精确定位权限，可在系统设置中授权后重试"); }
    }
    private void hideKeyboard() { search.clearFocus(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return UiKit.dp(this,value); }
    @Override protected void onPause() { resumed=false;if(realFinder!=null) { realFinder.cancel();realFinder=null;realPosition.setEnabled(true);realPosition.setText("真实位置"); } if(mapView!=null) mapView.onPause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putDouble("selected_lat",latitude); state.putDouble("selected_lon",longitude); state.putBoolean("has_point",hasPoint); state.putString("selected_title",selectedTitle); state.putString("selected_address",selectedAddress); if(mapView!=null) mapView.onSaveInstanceState(state); super.onSaveInstanceState(state); }
    @Override public void onLowMemory() { super.onLowMemory(); if(mapView!=null) mapView.onLowMemory(); }
    @Override public void onDestroy() { searchGeneration++; geocodeGeneration++; if(mapView!=null) mapView.onDestroy(); worker.shutdown(); super.onDestroy(); }
}
