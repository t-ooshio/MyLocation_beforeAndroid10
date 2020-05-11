package jp.sio.testapp.mylocation.Activity;

import android.content.Context;
import android.content.pm.ActivityInfo;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import jp.sio.testapp.mylocation.L;
import jp.sio.testapp.mylocation.Presenter.MyLocationPresenter;
import jp.sio.testapp.mylocation.R;


public class MyLocationActivity extends AppCompatActivity {

    private Button buttonStart;
    private Button buttonStop;
    private Button buttonSetting;
    private TextView tvResult;
    private TextView tvState;
    private TextView tvSetting;

    private Context context = this;
    private MyLocationPresenter presenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //縦方向に固定
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        L.d("onCreate");
        presenter = new MyLocationPresenter(this);

        buttonStart = (Button)findViewById(R.id.buttonStart);
        buttonStop = (Button)findViewById(R.id.buttonStop);
        buttonSetting = (Button)findViewById(R.id.buttonSetting);
        tvResult = (TextView)findViewById(R.id.textViewResult);
        tvState = (TextView)findViewById(R.id.textViewState);
        tvSetting = (TextView)findViewById(R.id.textViewSetting);

        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //UI操作
                offBtnStart();
                onBtnStop();
                offBtnSetting();

                pushBtnStart();
            }
        });
        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //UI操作
                onBtnStart();
                offBtnStop();
                onBtnSetting();

                pushBtnStop();
            }
        });
        buttonSetting.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pushBtnSetting();
            }
        });
        presenter.checkPermission();
        presenter.mStart();

    }
    @Override
    protected void onStart(){
        L.d("onStart");
        super.onStart();
    }
    public void showTextViewResult(String str){
        tvResult.setText(str);
    }
    public void showTextViewState(String str){tvState.setText(str + "\n");}
    public void showTextViewSetting(String str){
        tvSetting.setText(str);
    }

    public void pushBtnStart(){
        showTextViewState("測位中");
        presenter.locationStart();

    }
    public void pushBtnStop(){
        showTextViewState("停止");
        presenter.locationStop();
    }
    public void pushBtnSetting(){
        presenter.settingStart();
    }

    public void onBtnStart(){
        buttonStart.setEnabled(true);
    }
    public void offBtnStart(){
        buttonStart.setEnabled(false);
    }

    public void onBtnStop(){
        buttonStop.setEnabled(true);
    }
    public void offBtnStop(){
        buttonStop.setEnabled(false);
    }

    public void onBtnSetting(){
        buttonSetting.setEnabled(true);
    }
    public void offBtnSetting(){
        buttonSetting.setEnabled(false);
    }

    public void showToast(String message){
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){
        super.onKeyDown(keyCode,event);
        if(keyCode == KeyEvent.KEYCODE_BACK){
            showToast("KEYCODE_BACK");
            L.d("KEYCODE_BACK");
            presenter.locationStop();
            return super.onKeyDown(keyCode,event);
        }else{
            return super.onKeyDown(keyCode,event);
        }
    }
    @Override
    protected void onDestroy(){
        presenter.locationStop();
        super.onDestroy();
    }
}