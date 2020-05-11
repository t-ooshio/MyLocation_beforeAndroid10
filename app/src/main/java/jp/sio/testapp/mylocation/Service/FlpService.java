package jp.sio.testapp.mylocation.Service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.SettingsClient;

import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import jp.sio.testapp.mylocation.L;
import jp.sio.testapp.mylocation.R;
import jp.sio.testapp.mylocation.Repository.LocationLog;

/**
 * FLP測位を行うためのService
 * Created by NTT docomo on 2017/05/22.
 */

public class FlpService extends Service implements
        //com.google.android.gms.location.LocationListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener{

    private GoogleApiClient mGoogleApiClient;
    //private FusedLocationProviderApi fusedLocationProviderApi;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationRequest locationRequest;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    private LocationCallback locationCallback;
    private LocationManager locationManager;

    private Handler resultHandler;
    private Handler intervalHandler;
    private Handler stopHandler;
    private Timer stopTimer;
    private Timer intervalTimer;
    private StopTimerTask stopTimerTask;
    private IntervalTimerTask intervalTimerTask;

    //設定値の格納用変数
    private final String locationType = "flp";
    private int settingCount;   // 0の場合は無制限に測位を続ける
    private long settingInterval;
    private long settingTimeout;
    private boolean settingIsCold;
    private int settingSuplEndWaitTime;
    private int settingDelAssistdatatime;

    //SUPLEND WaitTimeの処理関連
    //onLocationChangeをCountして1回目のみで測位成功時の動作
    //locationSuccessの呼び出しをする処理を作る
    private int locationChangeCount;

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

    //ログ出力用のヘッダー文字列 Settingのヘッダーと測位結果のヘッダー
    private String settingHeader;
    private String locationHeader;

    /**
     * Locationのコールバックを受け取る
     */
    private void createLocationCallback(){
        locationCallback = new LocationCallback(){
            @Override
            public void onLocationResult(LocationResult locationResult){
                super.onLocationResult(locationResult);
                locationChangeCount++;
                L.d("onLocationChanged," + "locationChangeCount:" + locationChangeCount);
                if(locationChangeCount == 1){
                    locationSuccess(locationResult.getLastLocation());
                }
            }
        };
    }

    /**
     * Locationのコールバックの削除
     */
    private void deleteLocationCallback(){
        locationCallback = null;
    }

    @Override
    public void onConnected(Bundle bundle) {
        L.d("onCennected");
        locationStart();
    }

    @Override
    public void onConnectionSuspended(int i) {
        L.d("onConnectionSuspended");
        if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST) {
            L.d("Connection lost.  Cause: Network Lost.");
        } else if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED) {
            L.d("Connection lost.  Reason: Service Disconnected");
        }
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        L.d("onCennectionFailed");
    }

    public class FlpService_Binder extends Binder {
        public FlpService getService() {
            return FlpService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        resultHandler = new Handler();
        intervalHandler = new Handler();
        stopHandler = new Handler();

        L.d("before mGoogleApiClient");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        L.d("after mGoogleApiClient");

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startid) {
        super.onStartCommand(intent, flags, startid);
        L.d("onStartCommand");

        //サービスがKillされるのを防止する処理
        //サービスがKillされにくくするために、Foregroundで実行する
        /*
        Notification notification = new Notification();
        startForeground(1, notification);
        */

        //画面が消灯しないようにする処理
        //画面が消灯しないようにPowerManagerを使用
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        //PowerManagerの画面つけっぱなし設定SCREEN_BRIGHT_WAKE_LOCK、非推奨の設定値だが試験アプリ的にはあったほうがいいので使用
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getString(R.string.locationFlp));
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


        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        //fusedLocationProviderClient = LocationServices.FusedLocationApi;
        fusedLocationProviderClient = new FusedLocationProviderClient(this);
        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if(!mGoogleApiClient.isConnected()){
            mGoogleApiClient.connect();
        }
        return START_STICKY;
    }

    /**
     * 測位を開始する時の処理
     */
    public void locationStart() {

        L.d("locationStart");
        locationChangeCount = 0;
        createLocationCallback();
        if (settingIsCold) {
            coldLocation(fusedLocationProviderClient,locationManager);
        }
        locationStartTime = System.currentTimeMillis();
        //MyLocationUsecaseで起動時にPermissionCheckを行っているのでここでは行わない
        try{
            fusedLocationProviderClient.requestLocationUpdates(locationRequest,locationCallback, Looper.myLooper());
        }catch (SecurityException e){
            e.printStackTrace();
        }
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
    public void locationSuccess(final Location location) {
        L.d("locationSuccess");
        //測位終了の時間を取得
        locationStopTime = System.currentTimeMillis();
        //測位タイムアウトのタイマーをクリア
        if (stopTimer != null) {
            stopTimer.cancel();
            stopTimer = null;
        }
        runningCount++;
        successCount++;
        isLocationFix = true;
        ttff = (double) (locationStopTime - locationStartTime) / 1000;
        //測位結果の通知
        resultHandler.post(new Runnable() {
            @Override
            public void run() {
                L.d("resultHandler.post");
                sendLocationBroadCast(isLocationFix, location, locationStartTime, locationStopTime);
            }
        });
        L.d(location.getLatitude() + " " + location.getLongitude());

        try {
            Thread.sleep(settingSuplEndWaitTime);
        } catch (InterruptedException e) {
            L.d(e.getMessage());
            e.printStackTrace();
        }
        if(fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
        deleteLocationCallback();

        //測位回数が設定値に到達しているかチェック
        if (runningCount == settingCount && settingCount != 0) {
            serviceStop();
        } else {
            //回数満了してなければ測位間隔Timerを設定して次の測位の準備
            L.d("SuccessのIntervalTimer");
            if (intervalTimer != null) {
                intervalTimer.cancel();
                intervalTimer = null;
            }
            intervalTimerTask = new FlpService.IntervalTimerTask();
            intervalTimer = new Timer();
            L.d("Interval:" + settingInterval);
            intervalTimer.schedule(intervalTimerTask, settingInterval);
        }
    }

    /**
     * 測位失敗の場合の処理
     * 今のところタイムアウトした場合のみを想定
     */
    public void locationFailed() {
        L.d("locationFailed");
        //測位終了の時間を取得
        locationStopTime = System.currentTimeMillis();
        runningCount++;
        failCount++;
        isLocationFix = false;
        if(fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
        deleteLocationCallback();

        ttff = (double) (locationStopTime - locationStartTime) / 1000;

        if (stopTimer != null) {
            stopTimer = null;
        }
        //測位結果の通知
        resultHandler.post(new Runnable() {
            @Override
            public void run() {
                L.d("resultHandler.post");
                Location location = new Location(LocationManager.GPS_PROVIDER);
                sendLocationBroadCast(isLocationFix, location, locationStartTime, locationStopTime);
            }
        });
        //測位回数が設定値に到達しているかチェック
        if (settingCount == runningCount && settingCount != 0) {
            serviceStop();
        } else {
            L.d("FailedのIntervalTimer");
            //回数満了してなければ測位間隔Timerを設定して次の測位の準備
            if (intervalTimer != null) {
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
    public void serviceStop() {
        L.d("serviceStop");
        if (stopTimer != null) {
            stopTimer.cancel();
            stopTimer = null;
        }
        if (intervalTimer != null) {
            intervalTimer.cancel();
            intervalTimer = null;
        }
        //Serviceを終わるときにForegroundも停止する
        stopForeground(true);
        sendServiceEndBroadCast();

        if(wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        if (powerManager != null) {
            powerManager = null;
        }
        stopSelf();
    }

    /*
    @Override
    public void onLocationChanged(final Location location) {
        locationChangeCount++;
        L.d("onLocationChanged," + "locationChangeCount:" + locationChangeCount);
        if(locationChangeCount == 1){
            locationSuccess(location);
        }
    }
    */

    @Override
    public void onDestroy(){
        L.d("onDestroy");
        serviceStop();
        super.onDestroy();
    }

    /**
     * アシストデータの削除
     */
    private void coldLocation(FusedLocationProviderClient flp,LocationManager lm){
        sendColdBroadCast(getResources().getString(R.string.categoryColdStart));
        L.d("coldBroadcast:" + getResources().getString(R.string.categoryColdStart));
        //flp.flushLocations();
        lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null);

        try {
            Thread.sleep(settingDelAssistdatatime);
        } catch (InterruptedException e) {
            L.d(e.getMessage());
            e.printStackTrace();
        }

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
     *
     * @param fix               測位成功:True 失敗:False
     * @param location          測位結果
     * @param locationStartTime 測位API実行の時間
     * @param locationStopTime  測位API停止の時間
     */
    protected void sendLocationBroadCast(Boolean fix, Location location, long locationStartTime, long locationStopTime) {
        L.d("sendLocation");
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationFlp));
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
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationFlp));

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
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationFlp));
        broadcastIntent.putExtra(getResources().getString(R.string.category),getResources().getString(R.string.categoryServiceEnd));
        sendBroadcast(broadcastIntent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true; // 再度クライアントから接続された際に onRebind を呼び出させる場合は true を返す
    }
    @Override
    public IBinder onBind(Intent intent) {
        return new FlpService_Binder();
    }

    @Override
    public void onRebind(Intent intent) {
    }
}