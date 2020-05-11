package jp.sio.testapp.mylocation.Service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import jp.sio.testapp.mylocation.L;
import jp.sio.testapp.mylocation.R;
import jp.sio.testapp.mylocation.Repository.LocationLog;

/**
 * Network測位を行うためのService
 * Created by NTT docomo on 2017/05/22.
 */

public class NetworkService extends Service implements LocationListener {

    private LocationManager locationManager;
    private LocationLog locationLog;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    private Handler resultHandler;
    private Handler intervalHandler;
    private Handler stopHandler;
    private Timer stopTimer;
    private Timer intervalTimer;
    private StopTimerTask stopTimerTask;
    private IntervalTimerTask intervalTimerTask;

    //設定値の格納用変数
    private final String locationType = "Network";
    private int settingCount;   // 0の場合は無制限に測位を続ける
    private long settingInterval;
    private long settingTimeout;
    private boolean settingIsCold;
    private int settingSuplEndWaitTime;
    private int settingDelAssistdatatime;

    //測位中の測位回数
    private int runningCount;
    private int successCount;
    private int failCount;

    private double ttff;

    //測位成功の場合:true 測位失敗の場合:false を設定
    private boolean isLocationFix;

    //測位開始時間、終了時間
    private Calendar calendar = Calendar.getInstance();
    private long locationStartTime;
    private long locationStopTime;
    SimpleDateFormat simpleDateFormatHH = new SimpleDateFormat("HH:mm:ss.SSS");

    //ログ出力用のヘッダー文字列 Settingのヘッダーと測位結果のヘッダー
    private String settingHeader;
    private String locationHeader;

    public class NwService_Binder extends Binder {
        public NetworkService getService() {
            return NetworkService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        resultHandler = new Handler();
        intervalHandler = new Handler();
        stopHandler = new Handler();

        settingHeader = getResources().getString(R.string.settingHeader);
        locationHeader = getResources().getString(R.string.locationHeader);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startid) {
        super.onStartCommand(intent, flags, startid);
        L.d("onStartCommand");

        //サービスがKillされるのを防止する処理
        //サービスがKillされにくくするために、Foregroundで実行する
        //Notification notification = new Notification();
        //startForeground(1, notification);

        //画面が消灯しないようにする処理
        //画面が消灯しないようにPowerManagerを使用
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        //PowerManagerの画面つけっぱなし設定SCREEN_BRIGHT_WAKE_LOCK、非推奨の設定値だが試験アプリ的にはあったほうがいいので使用
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getString(R.string.locationNw));
        wakeLock.acquire();

        //設定値の取得
        // *1000は sec → msec の変換
        settingCount = intent.getIntExtra(getBaseContext().getString(R.string.settingCount), 0);
        settingTimeout = intent.getLongExtra(getBaseContext().getString(R.string.settingTimeout), 0) * 1000;
        settingInterval = intent.getLongExtra(getBaseContext().getString(R.string.settingInterval), 0) * 1000;
        settingIsCold = intent.getBooleanExtra(getBaseContext().getString(R.string.settingIsCold), true);
        settingSuplEndWaitTime = intent.getIntExtra(getResources().getString(R.string.settingSuplEndWaitTime), 0) * 1000;
        settingDelAssistdatatime = intent.getIntExtra(getResources().getString(R.string.settingDelAssistdataTime), 0) * 1000;
        runningCount = 0;
        successCount = 0;
        failCount = 0;


        //ログファイルの生成
        locationLog = new LocationLog(this);
        locationLog.makeLogFile(settingHeader);
        locationLog.writeLog(
                locationType + "," + settingCount + "," + settingTimeout
                        + "," + settingInterval + "," + settingSuplEndWaitTime + ","
                        + settingDelAssistdatatime + "," + settingIsCold);
        locationLog.writeLog(locationHeader);
        L.d("count:" + settingCount + " Timeout:" + settingTimeout + " Interval:" + settingInterval);
        L.d("suplendwaittime" + settingSuplEndWaitTime + " " + "DelAssist" + settingDelAssistdatatime);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationStart();

        return START_STICKY;
    }

    /**
     * 測位を開始する時の処理
     */
    public void locationStart() {

        L.d("locationStart");

        if (settingIsCold) {
            coldLocation(locationManager);
        }
        locationStartTime = System.currentTimeMillis();
        //MyLocationUsecaseで起動時にPermissionCheckを行っているのでここでは行わない
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
        L.d("requestLocationUpdates");

        //測位停止Timerの設定
        L.d("SetStopTimer");
        stopTimerTask = new StopTimerTask();
        stopTimer = new Timer(true);
        stopTimer.schedule(stopTimerTask,settingTimeout);
    }

    /**
     * 測位成功の場合の処理
     */
    public void locationSuccess(final Location location){
        L.d("locationSuccess");
        //測位終了の時間を取得
        locationStopTime = System.currentTimeMillis();
        //測位タイムアウトのタイマーをクリア
        if(stopTimer != null) {
            stopTimer.cancel();
        }
        runningCount++;
        successCount++;
        isLocationFix = true;
        ttff = (double)(locationStopTime - locationStartTime) / 1000;
        //測位結果の通知
        resultHandler.post(new Runnable() {
            @Override
            public void run() {
                L.d("resultHandler.post");
                sendLocationBroadCast(isLocationFix,location,ttff);
            }
        });
        locationLog.writeLog(
                simpleDateFormatHH.format(locationStartTime)  + "," +
                simpleDateFormatHH.format(locationStopTime) + "," + isLocationFix + "," +
                location.getLatitude() + "," + location.getLongitude() + "," + ttff + "," + location.getAccuracy()
        );
        L.d(location.getLatitude() + " " + location.getLongitude());

        try {
            Thread.sleep(settingSuplEndWaitTime);
        } catch (InterruptedException e) {
            L.d(e.getMessage());
            e.printStackTrace();
        }
        if(locationManager != null) {
            locationManager.removeUpdates(this);
        }

        //測位回数が設定値に到達しているかチェック
        if(runningCount == settingCount && settingCount != 0){
            serviceStop();
        }else{
            //回数満了してなければ測位間隔Timerを設定して次の測位の準備
            L.d("SuccessのIntervalTimer");
            if(intervalTimer != null){
                intervalTimer.cancel();
                intervalTimer = null;
            }
            intervalTimerTask = new IntervalTimerTask();
            intervalTimer = new Timer(true);
            L.d("Interval:" + settingInterval);
            intervalTimer.schedule(intervalTimerTask, settingInterval);
        }
    }

    /**
     * 測位失敗の場合の処理
     * 今のところタイムアウトした場合のみを想定
     */
    public void locationFailed(){
        L.d("locationFailed");
        //測位終了の時間を取得
        locationStopTime = System.currentTimeMillis();
        runningCount++;
        failCount++;
        isLocationFix = false;
        locationManager.removeUpdates(this);
        ttff = (double)(locationStopTime - locationStartTime) / 1000;

        //測位結果の通知
        resultHandler.post(new Runnable() {
            @Override
            public void run() {
                L.d("resultHandler.post");
                Location location = new Location(LocationManager.GPS_PROVIDER);
                sendLocationBroadCast(isLocationFix,location,ttff);
            }
        });
        locationLog.writeLog(
                simpleDateFormatHH.format(locationStartTime)  + "," +
                simpleDateFormatHH.format(locationStopTime) + "," + isLocationFix + "," +
                "-1" + "," + "-1" + "," + ttff
        );
        //測位回数が設定値に到達しているかチェック
        if(settingCount == runningCount && settingCount != 0){
            serviceStop();
        }else{
            L.d("FailedのIntervalTimer");
            //回数満了してなければ測位間隔Timerを設定して次の測位の準備
            if(intervalTimer != null){
                intervalTimer.cancel();
                intervalTimer = null;
            }
            intervalTimerTask = new IntervalTimerTask();
            intervalTimer = new Timer(true);
            L.d("Interval:" + settingInterval);
            intervalTimer.schedule(intervalTimerTask, settingInterval);
        }
    }
    /**
     * 測位が終了してこのServiceを閉じるときの処理
     * 測位回数満了、停止ボタンによる停止を想定した処理
     */
    public void serviceStop(){
        L.d("serviceStop");
        if(locationManager != null){
            locationManager.removeUpdates(this);
            locationManager = null;
        }
        if(stopTimer != null){
            stopTimer.cancel();
            stopTimer = null;
        }
        if(intervalTimer != null){
            intervalTimer.cancel();
            intervalTimer = null;
        }
        locationLog.endLogFile();
        //Serviceを終わるときにForegroundも停止する
        stopForeground(true);
        sendServiceEndBroadCast();

        wakeLock.release();
        if(powerManager != null) {
            powerManager = null;
        }
        //locationLog.endLogFile();
    }

    @Override
    public void onLocationChanged(final Location location) {
        locationSuccess(location);
    }

    @Override
    public void onDestroy(){
        L.d("onDestroy");
        serviceStop();
        super.onDestroy();
    }

    /**
     * アシストデータの削除
     */
    private void coldLocation(LocationManager lm){
        sendColdBroadCast(getResources().getString(R.string.categoryColdStart));
        L.d("coldBroadcast:" + getResources().getString(R.string.categoryColdStart));
        boolean coldResult = lm.sendExtraCommand(LocationManager.NETWORK_PROVIDER,"delete_aiding_data",null);
        try {
            Thread.sleep(settingDelAssistdatatime);
        } catch (InterruptedException e) {
            L.d(e.getMessage());
            e.printStackTrace();
        }

        L.d("delete_aiding_data:result " + coldResult);
        sendColdBroadCast(getResources().getString(R.string.categoryColdStop));
    }

    /**
     * 測位停止タイマー
     * 測位タイムアウトしたときの処理
     */
    class StopTimerTask extends TimerTask{

        @Override
        public void run() {
            stopHandler.post(new Runnable() {
                @Override
                public void run() {
                    L.d("StopTimerTask");
                    locationFailed();
                }
            });
        }
    }

    /**
     * 測位間隔タイマー
     * 測位間隔を満たしたときの次の動作（次の測位など）を処理
     */
    class IntervalTimerTask extends TimerTask{

        @Override
        public void run() {
            intervalHandler.post(new Runnable() {
                @Override
                public void run() {
                    L.d("IntervalTimerTask");
                    locationStart();
                }
            });
        }
    }

    /**
     * 測位完了を上に通知するBroadcast 測位結果を入れる
     * @param fix 測位成功:True 失敗:False
     * @param location 測位結果
     * @param ttff 測位API実行～測位停止までの時間
     */
    protected void sendLocationBroadCast(Boolean fix,Location location,double ttff){
        L.d("sendLocation");
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationNw));
        broadcastIntent.putExtra(getResources().getString(R.string.category), getResources().getString(R.string.categoryLocation));
        broadcastIntent.putExtra(getResources().getString(R.string.TagisFix), fix);
        broadcastIntent.putExtra(getResources().getString(R.string.TagLocation), location);
        broadcastIntent.putExtra(getResources().getString(R.string.Tagttff), ttff);
        broadcastIntent.putExtra(getResources().getString(R.string.TagLocationStarttime), locationStartTime);
        broadcastIntent.putExtra(getResources().getString(R.string.TagSuccessCount),successCount);
        broadcastIntent.putExtra(getResources().getString(R.string.TagFailCount),failCount);
        broadcastIntent.putExtra(getResources().getString(R.string.TagLocationStoptime), locationStopTime);

        sendBroadcast(broadcastIntent);
    }

    /**
     * Cold化(アシストデータ削除)の開始と終了を通知するBroadcast
     * 削除開始:categoryColdStart 削除終了:categoryColdStop
     * @param category
     */
    protected void sendColdBroadCast(String category){
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationNw));

        if(category.equals(getResources().getString(R.string.categoryColdStart))){
            L.d("ColdStart");
            broadcastIntent.putExtra(getResources().getString(R.string.category),getResources().getString(R.string.categoryColdStart));
        }else if(category.equals(getResources().getString(R.string.categoryColdStop))){
            L.d("ColdStop");
            broadcastIntent.putExtra(getResources().getString(R.string.category),getResources().getString(R.string.categoryColdStop));
        }
        sendBroadcast(broadcastIntent);
    }

    /**
     * Serviceを破棄することを通知するBroadcast
     */
    protected void sendServiceEndBroadCast(){
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationNw));
        broadcastIntent.putExtra(getResources().getString(R.string.category),getResources().getString(R.string.categoryServiceEnd));
        sendBroadcast(broadcastIntent);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }
    @Override
    public void onProviderEnabled(String provider) {

    }
    @Override
    public void onProviderDisabled(String provider) {

    }
    @Override
    public boolean onUnbind(Intent intent) {
        return true; // 再度クライアントから接続された際に onRebind を呼び出させる場合は true を返す
    }
    @Override
    public IBinder onBind(Intent intent) {
        return new NwService_Binder();
    }

    @Override
    public void onRebind(Intent intent) {
    }
}