package com.avatar.nputest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Complete local-avatar conversation; cloud text/voice and phone inference are separate stages. */
public final class ChatActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private AvatarView avatar;
    private TextView title,status,metrics;
    private String businessStatus="正在准备人物和对话";
    private ListView transcript;
    private TranscriptAdapter transcriptAdapter;
    private EditText input;
    private Button language,clear,settings,send,stop;
    private Button experienceMode,interactionMode,record,stopDemo;
    private LinearLayout chatControls,demoPanel;
    private TextView demoTitle,demoHint;
    private VoiceRecorder recorder;
    private boolean experience=true,recordHeld;
    private long recordingStarted;
    private CredentialStore credentials;
    private CloudConversation cloud;
    private CloudConversation.Reply reply;
    private AvatarSession engine;
    private long experienceUtteranceId;
    private AvatarSession.Metrics experienceMetrics;
    private final List<CloudConversation.Message> history=new ArrayList<>();
    private long active;
    private volatile boolean ready,destroyed,background;
    private String prompt="";
    private final StringBuilder response=new StringBuilder();
    private boolean committed=true;
    private boolean historyDirty,historyLoaded;
    private boolean english;
    private File assets;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        assets=new File(getExternalFilesDir(null),"avatar");
        credentials=new CredentialStore(this);
        english=getPreferences(MODE_PRIVATE).getBoolean("ui_english",false);
        experience=saved==null||saved.getBoolean("experience_mode",true);
        buildUi();
        cloud=new CloudConversation(new CloudConversation.Listener(){
            public void onText(long id,String delta){ui.post(()->{if(id==active){response.append(delta);refreshTranscript(false);}});}
            public void onPcm(long id,float[] pcm){
                if(id!=active)return;
                try{engine.pushAudio(id,pcm);}catch(IllegalArgumentException error){ui.post(()->{if(id==active)fail("语音输入异常，请重新发送",error);});}
            }
            public void onAudioEnd(long id){engine.endAudio(id);}
            public void onFailure(long id,String message){ui.post(()->{if(id==active)fail(message,null);});}
        });
        engine=new AvatarSession(this,avatar,assets,new AvatarSession.Listener(){
            public void onReady(){ready=true;updateControls();showStatus(idleStatus());}
            public void onProgress(String message){showStatus(message);}
            public void onSurfaceAvailable(boolean available){
                updateControls();
                if(ready&&!background&&recorder==null){
                    String working=active==0?idleStatus():experience?"正在演示你的声音":"正在回答";
                    if(!available&&businessStatus.equals(working))showStatus("正在恢复视频");
                    else if(available&&businessStatus.equals("正在恢复视频"))showStatus(working);
                }
            }
            public void onEnded(long id){if(active==id){commitTurn();active=0;updateControls();showStatus(idleStatus());}}
            public void onError(long id,String message,Throwable error){if(id==0){ready=false;send.setEnabled(false);}fail(message,error);}
        });
        engine.prepare();
        ui.post(tick);
        new AlertDialog.Builder(this)
                .setTitle("使用声明 / Usage Notice")
                .setMessage("本模型仅供非商业使用。商业授权请联系：\nwupingyu@mail.ustc.edu.cn\n\n"
                        + "This model is for non-commercial use only. For commercial licensing, contact:\n"
                        + "wupingyu@mail.ustc.edu.cn")
                .setPositiveButton("我已知晓 / Continue", null)
                .setCancelable(false)
                .show();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(19,25,32));
        getWindow().setNavigationBarColor(Color.rgb(19,25,32));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(Color.rgb(19,25,32));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),dp(8),dp(6),dp(6));
        title=text("",19,Color.WHITE);title.setId(R.id.app_title);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        language=new Button(this);language.setId(R.id.switch_language);compact(language);language.setOnClickListener(v->toggleLanguage());header.addView(language,new LinearLayout.LayoutParams(dp(56),dp(42)));
        clear=new Button(this);clear.setId(R.id.clear_history);compact(clear);clear.setOnClickListener(v->clearHistory());header.addView(clear,new LinearLayout.LayoutParams(dp(72),dp(42)));
        settings=new Button(this);settings.setId(R.id.open_settings);compact(settings);settings.setOnClickListener(v->settings());header.addView(settings,new LinearLayout.LayoutParams(dp(76),dp(42)));layout.addView(header);
        LinearLayout modes=new LinearLayout(this);modes.setPadding(dp(14),0,dp(14),dp(8));
        experienceMode=new Button(this);experienceMode.setId(R.id.experience_mode);compact(experienceMode);experienceMode.setTextSize(14);experienceMode.setOnClickListener(v->setMode(true));
        interactionMode=new Button(this);interactionMode.setId(R.id.interaction_mode);compact(interactionMode);interactionMode.setTextSize(14);interactionMode.setOnClickListener(v->setMode(false));
        modes.addView(experienceMode,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams modeGap=new LinearLayout.LayoutParams(0,dp(44),1);modeGap.leftMargin=dp(8);modes.addView(interactionMode,modeGap);layout.addView(modes);
        FrameLayout scene=new FrameLayout(this);avatar=new AvatarView(this);scene.addView(avatar,new FrameLayout.LayoutParams(-1,-1));
        metrics=text("",12,Color.WHITE);metrics.setId(R.id.metrics);metrics.setSingleLine(true);metrics.setBackgroundColor(0xAA131920);metrics.setPadding(dp(12),dp(7),dp(12),dp(7));
        FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);overlay.setMargins(dp(10),dp(10),0,0);scene.addView(metrics,overlay);
        layout.addView(scene,new LinearLayout.LayoutParams(-1,0,1));
        status=text(businessStatus,13,0xFF9CDCD5);status.setId(R.id.status);status.setPadding(dp(16),dp(7),dp(16),dp(6));layout.addView(status);
        transcript=new ListView(this);transcript.setId(R.id.conversation_list);transcript.setDivider(null);transcript.setStackFromBottom(true);transcript.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        transcriptAdapter=new TranscriptAdapter();transcript.setAdapter(transcriptAdapter);
        layout.addView(transcript,new LinearLayout.LayoutParams(-1,dp(140)));
        LinearLayout controls=new LinearLayout(this);chatControls=controls;controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(12),dp(3),dp(12),dp(10));
        input=new EditText(this);input.setId(R.id.chat_input);input.setTextColor(Color.WHITE);input.setHintTextColor(0xFF9AA5AF);input.setTextSize(16);input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        input.setText(getPreferences(MODE_PRIVATE).getString("draft",""));input.setSelection(input.length());
        controls.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=new Button(this);send.setId(R.id.send_message);send.setAllCaps(false);send.setEnabled(false);send.setOnClickListener(v->sendPrompt(input.getText().toString()));controls.addView(send,new LinearLayout.LayoutParams(dp(70),dp(48)));
        stop=new Button(this);stop.setId(R.id.stop_reply);stop.setAllCaps(false);stop.setEnabled(false);stop.setOnClickListener(v->{cancel("已停止，可以继续提问");});controls.addView(stop,new LinearLayout.LayoutParams(dp(70),dp(48)));
        layout.addView(controls);
        demoPanel=new LinearLayout(this);demoPanel.setOrientation(LinearLayout.VERTICAL);demoPanel.setGravity(Gravity.CENTER);demoPanel.setPadding(dp(22),dp(12),dp(22),dp(18));
        demoTitle=text("",20,Color.WHITE);demoTitle.setGravity(Gravity.CENTER);demoPanel.addView(demoTitle);
        demoHint=text("",13,0xFFB2C3CD);demoHint.setGravity(Gravity.CENTER);demoHint.setPadding(0,dp(7),0,dp(16));demoPanel.addView(demoHint);
        record=new Button(this);record.setId(R.id.record_audio);record.setAllCaps(false);record.setTextSize(18);record.setMinHeight(0);
        record.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:startRecording();return true;
                case MotionEvent.ACTION_MOVE:if(recordHeld&&(event.getY() < -dp(32)||event.getY()>v.getHeight()+dp(32)||event.getX() < -dp(32)||event.getX()>v.getWidth()+dp(32)))cancel("录音已取消");return true;
                case MotionEvent.ACTION_UP:if(recordHeld)finishRecording();return true;
                case MotionEvent.ACTION_CANCEL:case MotionEvent.ACTION_POINTER_DOWN:if(recordHeld)cancel("录音已取消");return true;
                default:return true;
            }
        });
        record.setOnClickListener(v->{if(recordHeld)finishRecording();else if(recorder==null)startRecording();});
        demoPanel.addView(record,new LinearLayout.LayoutParams(-1,dp(72)));
        stopDemo=new Button(this);stopDemo.setId(R.id.stop_demo);compact(stopDemo);stopDemo.setTextColor(0xFFBDD0DA);stopDemo.setBackgroundColor(Color.TRANSPARENT);stopDemo.setOnClickListener(v->cancel("演示已停止"));
        demoPanel.addView(stopDemo,new LinearLayout.LayoutParams(-1,dp(42)));layout.addView(demoPanel);setContentView(layout);
        refreshLanguage();
        loadHistory();
    }
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private void compact(Button button){button.setAllCaps(false);button.setMinWidth(0);button.setTextSize(12);button.setPadding(dp(4),0,dp(4),0);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void showStatus(String message){businessStatus=message;refreshStatus();}
    private String t(String chinese,String englishText){return english?englishText:chinese;}
    private void toggleLanguage(){
        SharedPreferences preferences=getPreferences(MODE_PRIVATE);boolean existed=preferences.contains("ui_english"),next=!english;
        if(!preferences.edit().putBoolean("ui_english",next).commit()){
            SharedPreferences.Editor rollback=preferences.edit();if(existed)rollback.putBoolean("ui_english",english);else rollback.remove("ui_english");rollback.commit();
            showStatus("语言设置未能保存，请重试");return;
        }
        english=next;refreshLanguage();
    }
    private void refreshLanguage(){
        title.setText(english&&getPackageName().endsWith(".lab")?"NanoAvatar Lab":getString(R.string.app_name));
        language.setText(english?"中文":"EN");language.setContentDescription(t("切换到英文","Switch to Chinese"));
        clear.setText(t("清空记录","Clear"));settings.setText(t("设置","Settings"));
        input.setHint(t("输入你想说的话","Type a message"));send.setText(t("发送","Send"));stop.setText(t("停止","Stop"));
        experienceMode.setText(t("体验模式","Experience"));interactionMode.setText(t("交互模式","Conversation"));
        demoTitle.setText(t("让数字人说出你的声音","Give your avatar a voice"));
        demoHint.setText(t("长按录音，松开后观看口型推理\n无需 API，音频仅在本机处理，最长 30 秒","Hold to record, release to animate\nNo API needed. Audio stays on your device. Up to 30 seconds."));
        stopDemo.setText(t("停止演示","Stop playback"));updateModeUi();
        refreshStatus();refreshTranscriptLanguage();
        refreshMetrics();
    }
    private void refreshTranscriptLanguage(){
        if(transcriptAdapter==null)return;
        int first=transcript.getFirstVisiblePosition();View row=transcript.getChildAt(0);int top=row==null?0:row.getTop();
        transcriptAdapter.notifyDataSetChanged();if(row!=null)transcript.setSelectionFromTop(first,top);
    }
    private void refreshStatus(){if(status!=null)status.setText((english?englishStatus(businessStatus):businessStatus)+(!experience&&!historyLoaded?t(" · 对话记录暂时无法读取，请稍后重试"," · Conversation history is unavailable. Try again later."):historyDirty?t(" · 记录暂未保存，将重试"," · History is not saved yet. It will retry."):""));}

    private String idleStatus(){return experience?"人物已准备好，长按下方开始录音":credentials.get().isEmpty()?"请在设置中填写阿里云密钥":"可以继续提问";}
    private void setMode(boolean value){
        if(experience==value)return;
        cancel(null);saveDraft();experience=value;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        updateModeUi();refreshMetrics();if(ready)showStatus(idleStatus());else refreshStatus();
    }
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void updateModeUi(){
        demoPanel.setVisibility(experience?View.VISIBLE:View.GONE);
        transcript.setVisibility(experience?View.GONE:View.VISIBLE);chatControls.setVisibility(experience?View.GONE:View.VISIBLE);
        clear.setVisibility(experience?View.GONE:View.VISIBLE);settings.setVisibility(experience?View.GONE:View.VISIBLE);
        for(Button b:new Button[]{experienceMode,interactionMode}){boolean selected=(b==experienceMode)==experience;b.setSelected(selected);b.setTextColor(selected?0xFF10232A:0xFFA9BBC7);b.setBackground(rounded(selected?0xFFA8EBE4:0xFF22313D,12));}
        updateControls();
    }
    private void updateControls(){
        boolean available=engine!=null&&engine.isReady()&&!background;
        send.setEnabled(available&&!experience);stop.setEnabled(active!=0);
        record.setEnabled(available&&(recorder==null||recordHeld));
        record.setTextColor(record.isEnabled()?0xFF0A222A:0xFF82979F);
        record.setBackground(rounded(recordHeld?0xFFFFC8AD:record.isEnabled()?0xFF80E0D8:0xFF293E47,22));
        record.setText(recordHeld?t("松开开始演示","Release to animate"):recorder!=null?t("正在处理录音","Finishing recording"):t("长按录音","Hold to record"));
        record.setContentDescription(recordHeld?t("正在录音，松开播放，移出按钮取消","Recording. Release to play; slide away to cancel"):t("长按录音，松开后演示。无障碍模式下点按开始，再次点按结束","Hold to record and release to animate. With accessibility, tap to start and tap again to finish"));
        stopDemo.setVisibility(active!=0?View.VISIBLE:View.INVISIBLE);stopDemo.setEnabled(active!=0);
    }
    private void startRecording(){
        if(!experience||background||engine==null||!engine.isReady()||recorder!=null)return;
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},41);return;
        }
        cancel(null);recordHeld=true;recordingStarted=SystemClock.elapsedRealtime();
        final VoiceRecorder[] owner=new VoiceRecorder[1];
        VoiceRecorder next=new VoiceRecorder(new VoiceRecorder.Listener(){
            public void onFinished(float[] pcm){if(recorder!=owner[0])return;recorder=null;recordHeld=false;updateControls();playRecording(pcm);}
            public void onError(){if(recorder!=owner[0])return;recorder=null;recordHeld=false;updateControls();showStatus("无法录音，请检查麦克风权限或占用情况");}
        });
        owner[0]=next;recorder=next;next.start();record.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);showStatus("正在录音，松开演示，移出按钮取消");updateControls();
    }
    private void finishRecording(){if(recorder==null)return;recordHeld=false;recorder.finish();updateControls();}
    private void playRecording(float[] pcm){
        if(!experience||background||destroyed||!engine.isReady())return;
        if(pcm.length<6400){showStatus("录音太短，请长按说一句话");return;}
        cancel(null);long id=engine.begin();active=id;experienceUtteranceId=id;experienceMetrics=engine.metrics();
        engine.pushAudio(id,pcm);engine.endAudio(id);refreshMetrics();updateControls();showStatus("正在演示你的声音");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==41&&experience&&!destroyed)showStatus(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED?"麦克风已就绪，请再次长按录音":"未开启麦克风权限，可在系统设置中允许后重试");
    }
    private final class TranscriptAdapter extends BaseAdapter {
        public int getCount(){return history.size()+(!committed?2:0);}
        public CloudConversation.Message getItem(int position){
            if(position<history.size())return history.get(position);
            return position==history.size()?new CloudConversation.Message("user",prompt):new CloudConversation.Message("assistant",response.toString());
        }
        public long getItemId(int position){return position;}
        public View getView(int position,View convert,ViewGroup parent){
            LinearLayout row;
            if(convert instanceof LinearLayout)row=(LinearLayout)convert;
            else{row=new LinearLayout(ChatActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(16),dp(6),dp(16),dp(4));row.addView(text("",12,0xFF9CDCD5));TextView body=text("",16,Color.WHITE);body.setTextIsSelectable(true);row.addView(body);}
            CloudConversation.Message item=getItem(position);
            TextView label=(TextView)row.getChildAt(0),body=(TextView)row.getChildAt(1);String role=item.role.equals("user")?t("你","You"):t("助手","Assistant");
            if(!role.contentEquals(label.getText()))label.setText(role);if(!item.text.contentEquals(body.getText()))body.setText(item.text);
            return row;
        }
    }
    private void refreshTranscript(boolean forceBottom){
        if(transcriptAdapter==null)return;
        transcriptAdapter.notifyDataSetChanged();
        if(forceBottom)transcript.post(()->transcript.setSelection(Math.max(0,transcriptAdapter.getCount()-1)));
    }

    private void sendPrompt(String value) {
        String clean=value.trim();if(clean.isEmpty()||!ready||background||!engine.isReady())return;
        if(!historyLoaded){loadHistory();if(!historyLoaded)return;}
        if(credentials.get().isEmpty()){settings();return;}
        cancel(null);
        long id=engine.begin();active=id;
        prompt=clean;response.setLength(0);committed=false;refreshTranscript(true);
        input.setText("");saveDraft();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        stop.setEnabled(true);showStatus("正在回答");
        reply=cloud.start(id,clean,new ArrayList<>(history),credentials.get());
    }
    private void cancel(String message) {
        VoiceRecorder capture=recorder;recorder=null;recordHeld=false;if(capture!=null)capture.close();
        active=0;
        if(engine!=null)engine.cancel();
        CloudConversation.Reply pending=reply;reply=null;if(pending!=null)pending.cancel();
        commitTurn();if(stop!=null)stop.setEnabled(false);
        if(record!=null)updateControls();
        if(message!=null)showStatus(message);
    }
    private void fail(String message,Throwable error) {
        if(destroyed)return;
        if(error!=null)saveError("conversation",error);
        if(!committed&&input.getText().toString().trim().isEmpty()){input.setText(prompt);input.setSelection(input.length());saveDraft();}
        cancel(null);
        showStatus(message);
    }
    private void clearHistory() {
        cancel(null);
        try {
            writeHistory(Collections.emptyList());
            history.clear();historyDirty=false;historyLoaded=true;prompt="";response.setLength(0);refreshTranscript(true);
            showStatus("对话记录已清空");
        } catch(Exception error) {
            saveError("clear-conversation",error);showStatus("对话记录未能清空，请重试");
        }
    }
    private void commitTurn() {
        if(!historyLoaded)return;
        if(!committed){committed=true;history.add(new CloudConversation.Message("user",prompt));if(response.length()>0)history.add(new CloudConversation.Message("assistant",response.toString()));historyDirty=true;refreshTranscript(false);}
        if(!historyDirty)return;
        try{writeHistory(history);historyDirty=false;refreshStatus();}
        catch(Exception error){saveError("save-conversation",error);refreshStatus();}
    }
    private void writeHistory(List<CloudConversation.Message> messages)throws Exception {
        JSONArray array=new JSONArray();for(CloudConversation.Message m:messages)array.put(new JSONObject().put("role",m.role).put("text",m.text));
        byte[] data=array.toString().getBytes(StandardCharsets.UTF_8);
        android.util.AtomicFile file=new android.util.AtomicFile(new File(getFilesDir(),"conversation.json"));FileOutputStream output=null;
        try{output=file.startWrite();output.write(data);file.finishWrite(output);output=null;if(!Arrays.equals(file.readFully(),data))throw new IOException("Conversation write was not committed");}
        catch(Exception error){if(output!=null)file.failWrite(output);throw error;}
    }
    private void loadHistory() {
        try {File base=new File(getFilesDir(),"conversation.json");android.util.AtomicFile file=new android.util.AtomicFile(base);
            if(!base.exists()&&!new File(base.getPath()+".bak").exists()){historyLoaded=true;return;}
            byte[] data=file.readFully();JSONArray array=new JSONArray(new String(data,StandardCharsets.UTF_8));List<CloudConversation.Message> loaded=new ArrayList<>();for(int i=0;i<array.length();i++){JSONObject m=array.getJSONObject(i);loaded.add(new CloudConversation.Message(m.getString("role"),m.getString("text")));}history.clear();history.addAll(loaded);historyLoaded=true;refreshTranscript(true);}
        catch(Exception error){historyLoaded=false;saveError("load-conversation",error);}
        finally{refreshStatus();}
    }
    private void saveDraft(){if(input!=null)getPreferences(MODE_PRIVATE).edit().putString("draft",input.getText().toString()).apply();}
    private void settings() {
        boolean configured=!credentials.get().isEmpty();
        EditText field=new EditText(this);field.setId(R.id.api_key);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setHint(configured?t("已配置，输入新密钥可替换","Configured. Enter a new key to replace it"):t("阿里云 API Key","Aliyun API key"));field.setPadding(dp(20),dp(16),dp(20),dp(16));
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(t("演示服务设置","Demo service settings"))
            .setMessage(t("阿里云用于演示中的文字回复和语音合成。密钥仅加密保存在本机，本地数字人模型推理不需要密钥。","Aliyun provides the demo's text replies and speech synthesis. The key is encrypted and stored only on this device. Local avatar model inference does not require a key."))
            .setView(field).setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("保存","Save"),null);
        if(configured)builder.setNeutralButton(t("移除密钥","Remove key"),(d,w)->{
            try{credentials.set("");cancel(null);showStatus("密钥已移除，请在设置中重新配置");}
            catch(Exception error){showStatus("密钥未能移除，请重试");}
        });
        AlertDialog dialog=builder.create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String key=field.getText().toString().trim();
            if(key.isEmpty()){field.setError(t("请输入密钥，或取消以保留当前设置","Enter a key, or cancel to keep the current setting"));return;}
            try{credentials.set(key);dialog.dismiss();showStatus("已保存，可以开始对话");}
            catch(Exception error){field.setError(t("密钥未保存，请检查输入","Key was not saved. Check the input"));}
        }));dialog.show();
    }

    private String englishStatus(String message) {
        switch(message){
            case "人物已准备好，长按下方开始录音":return "Avatar ready. Hold the button to record";
            case "正在演示你的声音":return "Animating your voice";
            case "录音已取消":return "Recording cancelled";
            case "演示已停止":return "Playback stopped";
            case "无法录音，请检查麦克风权限或占用情况":return "Cannot record. Check microphone permission or whether another app is using it";
            case "正在录音，松开演示，移出按钮取消":return "Recording. Release to animate; slide away to cancel";
            case "录音太短，请长按说一句话":return "Recording too short. Hold the button and say a sentence";
            case "麦克风已就绪，请再次长按录音":return "Microphone ready. Hold the button again to record";
            case "未开启麦克风权限，可在系统设置中允许后重试":return "Microphone permission is off. Allow it in system settings and try again";
            case "正在准备人物和对话":return "Preparing avatar and conversation";
            case "正在准备人物和模型，首次启动需要一些时间":return "Preparing avatar and models; first launch may take a moment";
            case "缺少人物或模型，请下载完整版安装包或按源码说明配置":return "Avatar or models are missing. Download the complete APK or follow the source setup instructions";
            case "人物和模型准备失败，请检查存储空间后退出并重新打开应用":return "Could not prepare avatar and models. Check storage space, then close and reopen the app";
            case "语音输入异常，请重新发送":return "Voice input failed. Send again.";
            case "请在设置中填写阿里云密钥":return "Enter your Aliyun key in Settings";
            case "人物已准备好":return "Avatar is ready";
            case "正在回答":return "Replying";
            case "正在恢复视频":return "Restoring video";
            case "可以继续提问":return "Ready for another question";
            case "已停止，可以继续提问":return "Stopped. Ready for another question.";
            case "对话记录已清空":return "Conversation history cleared";
            case "对话记录未能清空，请重试":return "Could not clear conversation history. Try again.";
            case "密钥已移除，请在设置中重新配置":return "Key removed. Configure it again in Settings.";
            case "密钥未能移除，请重试":return "Could not remove the key. Try again.";
            case "已保存，可以开始对话":return "Saved. You can start a conversation.";
            case "语言设置未能保存，请重试":return "Could not save the language setting. Try again.";
            case "正在准备对话模型":return "Preparing conversation model";
            case "人物准备失败":return "Could not prepare the avatar";
            case "本次口播未能完成":return "Could not complete this reply";
            case "播放已停止":return "Playback stopped";
            case "视频渲染已停止":return "Video rendering stopped";
            case "视频资源无法打开":return "Could not open the video";
            case "无法启动云端对话，请检查输入后重试。":return "Could not start the cloud conversation. Check the input and try again.";
            case "阿里云大模型连接失败或超时，请检查网络和服务状态。":return "The Aliyun LLM connection failed or timed out. Check the network and service status.";
            case "大模型没有返回数据，请重新发送。":return "The LLM returned no data. Send again.";
            case "大模型响应格式异常，请重新发送。":return "The LLM response was malformed. Send again.";
            case "大模型流提前中断，请重新发送。":return "The LLM stream ended early. Send again.";
            case "大模型没有返回文本，请重新发送。":return "The LLM returned no text. Send again.";
            case "大模型返回错误，请检查账号额度与模型权限。":return "The LLM returned an error. Check the account quota and model permissions.";
            case "语音合成连接已关闭，请重新发送。":return "The speech synthesis connection closed. Send again.";
            case "语音合成启动超时，请重新发送。":return "Speech synthesis timed out while starting. Send again.";
            case "无法启动语音合成，请重新发送。":return "Could not start speech synthesis. Send again.";
            case "语音合成失败，请检查音色、模型权限和额度。":return "Speech synthesis failed. Check the voice, model permissions, and quota.";
            case "语音合成没有返回音频，请重新发送。":return "Speech synthesis returned no audio. Send again.";
            case "语音合成响应格式异常，请重新发送。":return "The speech synthesis response was malformed. Send again.";
            case "本次回答超过 90 秒，请缩短问题后重试。":return "This reply exceeded 90 seconds. Shorten the question and try again.";
            case "阿里云语音合成连接失败或超时，请检查网络和服务状态。":return "The Aliyun speech synthesis connection failed or timed out. Check the network and service status.";
            case "TTS 流未正常结束，请重新发送。":return "The TTS stream ended unexpectedly. Send again.";
            case "语音合成任务被中断，请重新发送。":return "The speech synthesis task was interrupted. Send again.";
            case "无法发送语音合成文本，请重新发送。":return "Could not send text for speech synthesis. Send again.";
            case "云端文本处理被中断，请重新发送。":return "Cloud text processing was interrupted. Send again.";
            case "TTS PCM 音频被截断。":return "The TTS PCM audio was truncated.";
        }
        if(message.startsWith("正在准备人物 "))return "Preparing avatar "+message.substring("正在准备人物 ".length());
        String llm="大模型请求失败（HTTP ";int end=message.indexOf("）");
        if(message.startsWith(llm)&&end>llm.length())return "LLM request failed (HTTP "+message.substring(llm.length(),end)+"). Check the key, region, and model permissions.";
        String tts="语音合成连接失败（HTTP ";end=message.indexOf("）");
        if(message.startsWith(tts)&&end>tts.length())return "Speech synthesis connection failed (HTTP "+message.substring(tts.length(),end)+"). Check the key and model permissions.";
        return message;
    }

    private void refreshMetrics(){
        AvatarSession.Metrics live=engine==null?null:engine.metrics();
        if(live!=null&&experienceUtteranceId!=0&&live.utteranceId==experienceUtteranceId)experienceMetrics=live;
        AvatarSession.Metrics m=experience?experienceMetrics:live;
        String latency=m==null?t("未开始","Not started"):m.firstFrameMs>=0?String.format(Locale.ROOT,english?"%.1f ms":"%.1f 毫秒",m.firstFrameMs):m.state.equals("idle")?t("未开始","Not started"):!m.state.equals("waiting")?t("未生成","No frame"):!m.hasAudio?t("等待音频","Waiting for audio"):String.format(Locale.ROOT,english?"Generating %.0f ms":"生成中 %.0f 毫秒",m.generatingMs);
        String fps=experience?(m==null?t("未开始","Not started"):m.completedInferenceFps>=0?String.format(Locale.ROOT,"%.1f",m.completedInferenceFps):m.state.equals("waiting")?t("计算中","Computing"):t("未完成","Incomplete")):String.format(Locale.ROOT,"%.1f",m==null?0:m.mouthFps);
        String shown=(experience?t("全量 FPS  ","Overall FPS  "):"FPS  ")+fps+t("    首帧计算  ","   First frame compute  ")+latency;
        if(!shown.contentEquals(metrics.getText()))metrics.setText(shown);
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(destroyed)return;
        refreshMetrics();
        if(recordHeld){double seconds=(SystemClock.elapsedRealtime()-recordingStarted)/1000d;record.setText(seconds>=30?t("已录满 30 秒，松开演示","30s recorded. Release to animate"):String.format(Locale.ROOT,english?"Release to animate · %.1fs":"松开演示 · %.1f 秒",seconds));}
        ui.postDelayed(this,250);
    }};

    private void saveError(String stage,Throwable error) {
        android.util.Log.e("NanoAvatar",stage+": "+error.getClass().getSimpleName());
    }
    @Override protected void onResume(){super.onResume();background=false;if(engine!=null)engine.setPaused(false);if(send!=null)updateControls();}
    @Override protected void onSaveInstanceState(Bundle state){saveDraft();state.putBoolean("experience_mode",experience);super.onSaveInstanceState(state);}
    @Override protected void onStop(){saveDraft();background=true;boolean interrupted=recorder!=null||active!=0;cancel(interrupted&&ready?idleStatus():null);if(engine!=null)engine.setPaused(true);super.onStop();}
    @Override protected void onDestroy(){destroyed=true;cancel(null);ui.removeCallbacksAndMessages(null);if(cloud!=null)cloud.close();if(engine!=null)engine.close();super.onDestroy();}
}
