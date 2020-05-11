package jp.sio.testapp.mylocation.Presenter;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.List;

import jp.sio.testapp.mylocation.Activity.MyLocationActivity;
import jp.sio.testapp.mylocation.Activity.SettingActivity;
import jp.sio.testapp.mylocation.L;
import jp.sio.testapp.mylocation.R;
import jp.sio.testapp.mylocation.Service.CurrentLocationService;
import jp.sio.testapp.mylocation.Service.FlpService;
import jp.sio.testapp.mylocation.Service.IareaService;
import jp.sio.testapp.mylocation.Service.NetworkService;
import jp.sio.testapp.mylocation.Service.UeaService;
import jp.sio.testapp.mylocation.Service.UebService;
import jp.sio.testapp.mylocation.Usecase.MyLocationUsecase;
import jp.sio.testapp.mylocation.Usecase.SettingUsecase ;
import jp.sio.testapp.mylocation.Repository.LocationLog;

/**
 * Created by NTT docomo on 2017/05/23.
 * ActivityとServiceの橋渡し
 * Activityはなるべく描画だけに専念させたいから分けるため
 */

public class MyLocationPresenter {
    private MyLocationActivity activity;
    private SettingUsecase settingUsecase;
    private MyLocationUsecase myLocationUsecase;
    private Intent settingIntent;
    private Intent locationserviceIntent;
    private ServiceConnection runService;
    private LocationLog locationLog;

    private String receiveCategory;
    private String categoryLocation;
    private String categoryColdStart;
    private String categoryColdStop;
    private String categoryServiceStop;

    private UebService uebService;
    private UeaService ueaService;
    private CurrentLocationService currentLocationService;
    private NetworkService networkService;
    private IareaService iareaService;
    private FlpService flpService;

    private String locationType;
    private int count;
    private long timeout;
    private long interval;
    private boolean isCold;
    private int suplendwaittime;
    private int delassisttime;

    private String settingHeader;
    private String locationHeader;


    private ServiceConnection serviceConnectionUeb = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            uebService = ((UebService.UebService_Binder)service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            activity.unbindService(runService);
            uebService = null;
        }
    };

    private ServiceConnection serviceConnectionUea = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            ueaService = ((UeaService.UeaService_Binder)service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            activity.unbindService(runService);
            ueaService = null;

        }
    };

    private ServiceConnection serviceConnectionCurrentLocation = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            currentLocationService = ((CurrentLocationService.CurrentLocationService_Binder)service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            activity.unbindService(runService);
            currentLocationService = null;

        }
    };

    private ServiceConnection serviceConnectionNetwork = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            networkService = ((NetworkService.NwService_Binder)service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            activity.unbindService(runService);
            networkService = null;
        }
    };
    private ServiceConnection serviceConnectionIarea = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            iareaService = ((IareaService.IareaService_Binder)service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            activity.unbindService(runService);
            iareaService = null;
        }
    };

    private ServiceConnection serviceConnectionFlp = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            flpService = ((FlpService.FlpService_Binder)service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            activity.unbindService(runService);
            iareaService = null;
        }
    };

    private final LocationReceiver locationReceiver = new LocationReceiver();

    public MyLocationPresenter(MyLocationActivity activity){
        this.activity = activity;
        myLocationUsecase = new MyLocationUsecase(activity);
        settingUsecase = new SettingUsecase(activity);

        categoryLocation = activity.getResources().getString(R.string.categoryLocation);
        categoryColdStart = activity.getResources().getString(R.string.categoryColdStart);
        categoryColdStop = activity.getResources().getString(R.string.categoryColdStop);
        categoryServiceStop = activity.getResources().getString(R.string.categoryServiceEnd);
        settingHeader = activity.getResources().getString(R.string.settingHeader) ;
        locationHeader =activity. getResources().getString(R.string.locationHeader);

    }

    public void checkPermission(){
        myLocationUsecase.hasPermissions();
    }

    public void mStart(){
        activity.offBtnStop();

        activity.showTextViewState(activity.getResources().getString(R.string.locationStop));
    }

    public void locationStart(){
        IntentFilter filter = null;
        getSetting();
        L.d(locationType + "," + count + "," + timeout
                + "," + interval + "," + suplendwaittime + ","
                + delassisttime + "," + isCold);
        //ログファイルの生成
        locationLog = new LocationLog(activity);
        L.d("before_makeLogFile");
        L.d(settingHeader);
        locationLog.makeLogFile(settingHeader);
        locationLog.writeLog(
                locationType + "," + count + "," + timeout
                        + "," + interval + "," + suplendwaittime + ","
                        + delassisttime + "," + isCold);
        locationLog.writeLog(locationHeader);

        activity.showTextViewSetting("測位方式:" + locationType + "\n" + "測位回数:" + count + "\n" + "タイムアウト:" + timeout + "\n" +
                "測位間隔:" + interval + "\n" + "Cold:" + isCold + "\n"
                + "suplEndWaitTime:" + suplendwaittime + "\n" + "アシストデータ削除時間:" + delassisttime + "\n");

        if(locationType.equals(activity.getResources().getString(R.string.locationUeb))) {
            L.d("after_UEBService");
            locationserviceIntent = new Intent(activity.getApplicationContext(), UebService.class);
            setSetting(locationserviceIntent);
            runService = serviceConnectionUeb;
            filter = new IntentFilter(activity.getResources().getString(R.string.locationUeb));
            L.d("before_UEBService");

        }else if(locationType.equals(activity.getResources().getString(R.string.locationUea))){
            locationserviceIntent = new Intent(activity.getApplicationContext(), UeaService.class);
            setSetting(locationserviceIntent);
            runService = serviceConnectionUea;
            filter = new IntentFilter(activity.getResources().getString(R.string.locationUea));

        }else if(locationType.equals(activity.getResources().getString(R.string.locationCurrent))){
            L.d("after_CurrentLocationService");
            locationserviceIntent = new Intent(activity.getApplicationContext(), CurrentLocationService.class);
            setSetting(locationserviceIntent);
            runService = serviceConnectionCurrentLocation;
            filter = new IntentFilter(activity.getResources().getString(R.string.locationCurrent));
            L.d("before_CurrentLocationService");

        }else if(locationType.equals(activity.getResources().getString(R.string.locationNw))){
            locationserviceIntent = new Intent(activity.getApplicationContext(), NetworkService.class);
            setSetting(locationserviceIntent);
            runService = serviceConnectionNetwork;
            filter = new IntentFilter(activity.getResources().getString(R.string.locationNw));

        }else if(locationType.equals(activity.getResources().getString(R.string.locationiArea))){
            locationserviceIntent = new Intent(activity.getApplicationContext(), IareaService.class);
            setSetting(locationserviceIntent);
            runService = serviceConnectionIarea;
            filter = new IntentFilter(activity.getResources().getString(R.string.locationiArea));

        }else if(locationType.equals(activity.getResources().getString(R.string.locationFlp))){
            locationserviceIntent = new Intent(activity.getApplicationContext(), FlpService.class);
            setSetting(locationserviceIntent);
            runService = serviceConnectionFlp;
            filter = new IntentFilter(activity.getResources().getString(R.string.locationFlp));

        }else{
            showToast("予期せぬ測位方式");
        }
        activity.startService(locationserviceIntent);
        activity.registerReceiver(locationReceiver,filter);
        activity.bindService(locationserviceIntent,runService ,Context.BIND_AUTO_CREATE);

    }

    /**
     * 測位回数満了などで測位を停止する処理
     */
    public void locationStop(){
        L.d("locationStop");

        L.d("ServiceConnectionの削除");
        if(runService != null) {
            L.d("unbindService");
            try {
                activity.unbindService(runService);
            }catch (IllegalArgumentException e){
                e.printStackTrace();
            }
        }


        //Service1の停止
        L.d("Serviceの停止");
        if(locationserviceIntent != null) {
            try {
                activity.stopService(locationserviceIntent);
            }catch(SecurityException e){
                e.printStackTrace();
            }
        }

        //Receiverの消去
        L.d("Receiverの消去");
        try {
            if (locationReceiver != null) {
                activity.unregisterReceiver(locationReceiver);
            }
        }catch (IllegalArgumentException e){
            e.printStackTrace();
        }

        //logファイルの終了
        L.d("logファイルの終了");
        if(locationLog != null) {
            locationLog.endLogFile();
        }
    }

    /**
     * Setting表示開始
     */
    public void settingStart(){
        settingIntent = new Intent(activity.getApplicationContext(), SettingActivity.class);
        activity.startActivity(settingIntent);
    }

    /**
     * activityにToastを表示する
     * @param message
     */
    public void showToast(String message){
        activity.showToast(message);
    }

    /**
     * 測位結果を受けとるためのReceiver
     */
    public class LocationReceiver extends BroadcastReceiver {
        Boolean isFix;
        double lattude, longitude, ttff;
        long fixtimeEpoch;
        String fixtimeUTC;
        String locationStarttime, locationStoptime;
        int sucCnt;
        int failCnt;

        Location location = new Location(LocationManager.GPS_PROVIDER);
        SimpleDateFormat fixTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZ");
        SimpleDateFormat simpleDateFormatHH = new SimpleDateFormat("HH:mm:ss.SSS");


        @Override
        public void onReceive(Context context, Intent intent) {
            L.d("onReceive");
            Bundle bundle = intent.getExtras();
            receiveCategory = bundle.getString(activity.getResources().getString(R.string.category));

            if (receiveCategory.equals(categoryLocation)) {
                L.d("Location Result onReceive");
                location = bundle.getParcelable(activity.getResources().getString(R.string.TagLocation));
                isFix = bundle.getBoolean(activity.getResources().getString(R.string.TagisFix));
                sucCnt = bundle.getInt(activity.getResources().getString(R.string.TagSuccessCount));
                failCnt = bundle.getInt(activity.getResources().getString(R.string.TagFailCount));
                locationStarttime = simpleDateFormatHH.format(bundle.getLong(activity.getResources().getString(R.string.TagLocationStarttime)));
                locationStoptime = simpleDateFormatHH.format(bundle.getLong(activity.getResources().getString(R.string.TagLocationStoptime)));
                if (isFix) {
                    lattude = location.getLatitude();
                    longitude = location.getLongitude();
                    fixtimeEpoch = location.getTime();
                    fixtimeUTC = fixTimeFormat.format(fixtimeEpoch);
                } else {
                    lattude = -1;
                    longitude = -1;
                    fixtimeEpoch = -1;
                    fixtimeUTC = "-1";
                }
                ttff = bundle.getDouble(activity.getResources().getString(R.string.Tagttff));
                L.d("onReceive");
                L.d(locationStarttime + "," + locationStoptime + "," + isFix + "," + lattude + "," + longitude + "," + ttff + ","
                        + sucCnt + "," + failCnt + "," + fixtimeEpoch + "," + fixtimeUTC + "\n");
                locationLog.writeLog(
                        locationStarttime + "," + locationStoptime + "," + isFix + "," + location.getLatitude() + "," + location.getLongitude()
                                + "," + ttff + "," + location.getAccuracy() + "," + fixtimeEpoch + "," + fixtimeUTC);

                activity.showTextViewResult("測位成否:" + isFix + "\n" + "緯度:" + lattude + "\n" + "経度:" + longitude + "\n" + "TTFF：" + ttff + "\n" + "Accuracy:" + location.getAccuracy()
                        + "\n" + "成功回数:" + sucCnt + "\n"  + "失敗回数:" + failCnt + "\n" + "fixTimeEpoch:" + fixtimeEpoch + "\n" + "fixTimeUTC:" + fixtimeUTC + "\n");

                activity.showTextViewState(activity.getResources().getString(R.string.locationWait));
            } else if (receiveCategory.equals(categoryColdStart)) {
                L.d("ReceiceColdStart");
                activity.showTextViewState(activity.getResources().getString(R.string.locationPositioning));
                showToast("アシストデータ削除中");
            } else if (receiveCategory.equals(categoryColdStop)) {
                L.d("ReceiceColdStop");
                showToast("アシストデータ削除終了");
            } else if (receiveCategory.equals(categoryServiceStop)) {
                L.d("ServiceStop");
                activity.showTextViewState(activity.getResources().getString(R.string.locationStop));
                showToast("測位サービス終了");
                activity.onBtnStart();
                activity.offBtnStop();
                activity.onBtnSetting();
            }
        }

        public void unreggister() {
            activity.unregisterReceiver(this);
        }
    }

    private void setSetting(Intent locationServiceIntent){
        locationServiceIntent.putExtra(activity.getResources().getString(R.string.settingCount),count);
        locationServiceIntent.putExtra(activity.getResources().getString(R.string.settingTimeout),timeout);
        locationServiceIntent.putExtra(activity.getResources().getString(R.string.settingInterval),interval);
        locationServiceIntent.putExtra(activity.getResources().getString(R.string.settingIsCold),isCold);
        locationServiceIntent.putExtra(activity.getResources().getString(R.string.settingSuplEndWaitTime),suplendwaittime);
        locationServiceIntent.putExtra(activity.getResources().getString(R.string.settingDelAssistdataTime),delassisttime);

    }

    /**
     * 設定画面で設定した値を取得する
     */
    private void getSetting(){
        locationType = settingUsecase.getLocationType();
        count = settingUsecase.getCount();
        timeout = settingUsecase.getTimeout();
        interval = settingUsecase.getInterval();
        isCold = settingUsecase.getIsCold();
        suplendwaittime = settingUsecase.getSuplEndWaitTime();
        delassisttime = settingUsecase.getDelAssistDataTime();

    }

    /**
     * clsに渡したServiceが起動中か確認する
     * true:  起動している
     * false: 起動していない
     * @param context
     * @param cls
     * @return
     */
    private boolean isServiceRunning(Context context, Class<?> cls){
        ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> runningService = am.getRunningServices(Integer.MAX_VALUE);
        for(ActivityManager.RunningServiceInfo i :runningService){
            if(cls.getName().equals(i.service.getClassName())){
                L.d(cls.getName());
                return true;
            }
        }
        return false;
    }
}