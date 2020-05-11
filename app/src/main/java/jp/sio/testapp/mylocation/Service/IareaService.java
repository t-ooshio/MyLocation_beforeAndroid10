package jp.sio.testapp.mylocation.Service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Xml;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import jp.sio.testapp.mylocation.L;
import jp.sio.testapp.mylocation.R;
import jp.sio.testapp.mylocation.Repository.LocationLog;

/**
 * OpeniArea測位を行うためのService
 * Created by NTT docomo on 2017/05/22.
 */

public class IareaService extends Service implements LocationListener {

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
    private final String locationType = "OpeniArea";
    private int settingCount;   // 0の場合は無制限に測位を続ける
    private long settingInterval;
    private long settingTimeout;
    private boolean settingIsCold;
    private int settingSuplEndWaitTime;
    private int settingDelAssistdatatime;

    //測位中の測位回数
    private int runningCount;
    private long ttff;

    //測位成功の場合:true 測位失敗の場合:false を設定
    private boolean isLocationFix;

    //測位開始時間、終了時間
    private Calendar calendar = Calendar.getInstance();
    private long locationStartTime;
    private long locationStopTime;
    SimpleDateFormat simpleDateFormatHH = new SimpleDateFormat("HH:mm:ss.sss");

    //ログ出力用のヘッダー文字列 Settingのヘッダーと測位結果のヘッダー
    private String settingHeader;
    private String locationHeader;

    //requestクエリの作成
    /** connection戻り値(正常終了)。 */
    public static final int RESULT_OK = 0;
    /** connection戻り値(異常終了)。 */
    public static final int RESULT_NG = -1;
    /** 接続タイムアウト(ミリ秒)。 */
    private static final int CONNECT_TIMEOUT = 20000; // サーバ仕様は10秒
    /** 読み取りタイムアウト(ミリ秒)。 */
    private static final int READ_TIMEOUT = 40000; // サーバ仕様は36秒
    /** 接続先URL。 */
    private static final String OPEN_IAREA_REQUEST_URL = "https://api.spmode.ne.jp/nwLocation/GetLocation";
    /** リクエストデータ。 */
    private static final String REQUEST_DATA = new String(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                    + "<DDF ver=\"1.0\">\r\n"
                    + "<RequestInfo>\r\n"
                    + "<RequestParam>\r\n"
                    + "<APIKey>\r\n"
                    + "<APIKey1_ID>"
                    + "xvgk4x85g2kjjce6"
                    + "</APIKey1_ID >\r\n"
                    + "<APIKey2> "
                    + "4d7z5tct"
                    + "</APIKey2>\r\n"
                    + "</APIKey>\r\n"
                    + "<OptionProperty>\r\n"
                    + "<AreaCode></AreaCode>\r\n"
                    + "<AreaName></AreaName>\r\n"
                    + "<Adr></Adr>\r\n"
                    + "<AdrCode></AdrCode>\r\n"
                    + "<PostCode></PostCode>\r\n"
                    + "</OptionProperty>\r\n"
                    + "</RequestParam>\r\n"
                    + "</RequestInfo>\r\n"
                    + "</DDF>\r\n"
    );
    /** Resultコード(未設定)。 */
    private static final int RESPONSECODE_NOT_SET = -1;
    /** Resultコード(正常下限)。 */
    private static final int RESPONSECODE_OKAY_LOWER = 2000;
    /** Resultコード(正常上限)。 */
    private static final int RESPONSECODE_OKAY_UPPER = 2999;

    /** XMLTAG Resultコード。 */
    private static final String XML_TAG_RESULTCODE = new String("ResultCode");
    /** XMLTAG 緯度。 */
    private static final String XML_TAG_GEO_LAT = new String("Lat");
    /** XMLTAG 経度。 */
    private static final String XML_TAG_GEO_LON = new String("Lon");
    /** XMLTAG 測位時刻。 */
    private static final String XML_TAG_GEO_TIME = new String("Time");
    /**XMLTAG エラーメッセージ*/
    private static final String XML_TAG_ERROR_MESSAGE = new String("Error");
    /**XMLTAG メッセージ*/
    private static final String XML_TAG_MESSAGE = new String("Message");
    /** XML 緯度経度フォーマット。 */
    private static final String XML_LATLON_FORMAT = new String("XYYY.ZZZZZ");

    private Location location;

    private URL requestUrl = null;
    private StringBuffer xmlQuery;
    private String resJsonObjName = "resJsonObjName";
    JSONObject jsonObject;

    public class IareaService_Binder extends Binder {
        public IareaService getService() {
            return IareaService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        resultHandler = new Handler();
        intervalHandler = new Handler();
        stopHandler = new Handler();
        jsonObject = new JSONObject();

        settingHeader = getResources().getString(R.string.settingHeader);
        locationHeader = getResources().getString(R.string.locationHeader);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startid) {
        super.onStartCommand(intent, flags, startid);
        L.d("onStartCommand");

        //サービスがKillされるのを防止する処理
        //サービスがKillされにくくするために、Foregroundで実行する
        Notification notification = new Notification();
        startForeground(1, notification);

        //画面が消灯しないようにする処理
        //画面が消灯しないようにPowerManagerを使用
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        //PowerManagerの画面つけっぱなし設定SCREEN_BRIGHT_WAKE_LOCK、非推奨の設定値だが試験アプリ的にはあったほうがいいので使用
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getString(R.string.locationiArea));
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

        try {
            requestUrl = new URL(OPEN_IAREA_REQUEST_URL);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        location = new Location(LocationManager.GPS_PROVIDER);
        locationStart();

        return START_STICKY;
    }

    /**
     * 測位を開始する時の処理
     */
    public void locationStart() {

        L.d("locationStart");

        //他の測位ではここでCold処理入れてるがiAreaには無い…はず
        locationStartTime = System.currentTimeMillis();

        connection();
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
        isLocationFix = true;
        ttff = (locationStopTime - locationStartTime) / 1000;
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
                location.getLatitude() + "," + location.getLongitude() + "," + ttff
        );
        L.d(location.getLatitude() + " " + location.getLongitude());

        try {
            Thread.sleep(settingSuplEndWaitTime);
        } catch (InterruptedException e) {
            L.d(e.getMessage());
            e.printStackTrace();
        }
        //測位回数が設定値に到達しているかチェック
        if(runningCount == settingCount && settingCount != 0){
            serviceStop();
        }else{
            //回数満了してなければ測位間隔Timerを設定して次の測位の準備
            L.d("SuccessのIntervalTimer");
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
        isLocationFix = false;
        ttff = (locationStopTime - locationStartTime) / 1000;

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

    /**
     * オープンiエリア測位通信。
     *
     * @return boolean
     */
    public int connection() {
        URL url = null;
        try {
            // URLを作成
            url = new URL(OPEN_IAREA_REQUEST_URL);
        } catch (MalformedURLException e) {
            L.d("OpeniAreaHttpConnect.connection():MalformedURLException");
            locationFailed();
            return RESULT_NG;
        }
        L.d("OpeniAreaHttpConnect.connection():conn OK");

        HttpURLConnection conn = null;
        try {
            // コネクションを作成
            conn = (HttpURLConnection) url.openConnection();
            url = null;
            // メソッドを設定 (POST)
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestMethod("POST");
            // リクエスト方式・ヘッダの設定
            conn.setRequestProperty("Content-Type", "Application/xml; charset=UTF-8");
            // リダイレクトの追跡は不要
            conn.setInstanceFollowRedirects(false);
            // タイムアウト設定
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
        } catch (ProtocolException e) {
            L.d("OpeniAreaHttpConnect.connection():MalformedURLException");
            locationFailed();
            return RESULT_NG;
        } catch (IOException e) {
            L.d("OpeniAreaHttpConnect.connection():IOException");
            locationFailed();
            return RESULT_NG;
        }
        StringBuilder response;
        try {
            // 接続
            conn.connect();
            // データ送信
            OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream());
            try {
                osw.write(REQUEST_DATA);
                osw.flush();
            } catch (IOException e) {
                L.d("OpeniAreaHttpConnect.connection():IOException");
                locationFailed();
                return RESULT_NG;
            } finally {
                osw.close();
            }
            // レスポンスデータを取得
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            try {
                String line;
                response = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                L.d("XML:" + response.toString());
            } catch (IOException e) {
                L.d("OpeniAreaHttpConnect.connection():IOException");
                locationFailed();
                return RESULT_NG;
            } finally {
                reader.close();
            }

        } catch (IOException e) {
            L.d("OpeniAreaHttpConnect.connection():IOException");
            locationFailed();
            return RESULT_NG;
        }
        finally {
            // 切断
            conn.disconnect();
        }
        // レスポンスデータ確認
        if (response.length() == 0) {
            locationFailed();
            return RESULT_NG;
        }
        // Resultコード・位置情報取得
        int resultcode;
        String latitude_text;
        String longitude_text;
        String error_message="nothing";
        @SuppressWarnings("unused")
        String time_text;
        try {
            resultcode = RESPONSECODE_NOT_SET;
            latitude_text = null;
            longitude_text = null;
            time_text = null;
            XmlPullParser parser = Xml.newPullParser();
            try {
                parser.setInput(new StringReader(response.toString()));
            } catch (XmlPullParserException e) {
                e.printStackTrace();
            }
            int e = parser.getEventType();

            while (e != XmlPullParser.END_DOCUMENT) {
                String tagname;
                if (e == XmlPullParser.START_TAG) {
                    tagname = parser.getName();
                    if (tagname.equals(XML_TAG_RESULTCODE)) {
                        // 次TAGを取得
                        resultcode = Integer.valueOf(parser.nextText());
                        // END_TAGに移動してなければ、TEXT部分にいると判断して次に移動(ICSより前verのバグ回避対応)
                        if (parser.getEventType() != XmlPullParser.END_TAG) {
                            parser.next();
                        }
                    } else if (tagname.equals(XML_TAG_GEO_LAT)) {

                        latitude_text = parser.nextText();
                        // END_TAGに移動してなければ、TEXT部分にいると判断して次に移動(ICSより前verのバグ回避対応)
                        if (parser.getEventType() != XmlPullParser.END_TAG) {
                            parser.next();
                        }
                    } else if (tagname.equals(XML_TAG_GEO_LON)) {

                        longitude_text = parser.nextText();
                        // END_TAGに移動してなければ、TEXT部分にいると判断して次に移動(ICSより前verのバグ回避対応)
                        if (parser.getEventType() != XmlPullParser.END_TAG) {
                            parser.next();
                        }
                    } else if (tagname.equals(XML_TAG_GEO_TIME)) {
                        time_text = parser.nextText();
                        // END_TAGに移動してなければ、TEXT部分にいると判断して次に移動(ICSより前verのバグ回避対応)
                        if (parser.getEventType() != XmlPullParser.END_TAG) {
                            parser.next();
                        }
                    } else if(tagname.equals(XML_TAG_ERROR_MESSAGE)){
                        //エラーの場合、エラーメッセージを読み込む
                        parser.next();
                        tagname = parser.getName();
                        L.d("OpeniAreaHttpConnect.connection():XML_ERROR:"+tagname);
                        locationFailed();

                        // END_TAGに移動してなければ、TEXT部分にいると判断して次に移動(ICSより前verのバグ回避対応)
                        if (parser.getEventType() != XmlPullParser.END_TAG) {
                            parser.next();
                        }

                        if(tagname.equals(XML_TAG_MESSAGE)){
                            //エラーメッセージ（URLが入ってくると想定）
                            error_message = parser.getText();
                            L.d("OpeniAreaHttpConnect.connection():error_message:"+error_message);
                            // END_TAGに移動してなければ、TEXT部分にいると判断して次に移動(ICSより前verのバグ回避対応)
                            if (parser.getEventType() != XmlPullParser.END_TAG) {
                                parser.next();
                            }
                        }
                    }
                }
                // 次要素へ
                e = parser.next();
            }
        } catch (NumberFormatException e) {
            L.d("OpeniAreaHttpConnect.connection():NumberFormatException");
            locationFailed();
            return RESULT_NG;
        } catch (XmlPullParserException e) {
            L.d("OpeniAreaHttpConnect.connection():XmlPullParserException");
            locationFailed();
            return RESULT_NG;
        } catch (IOException e) {
            L.d("OpeniAreaHttpConnect.connection():IOException");
            locationFailed();
            return RESULT_NG;
        }

        // Resultコード確認
        if (resultcode < RESPONSECODE_OKAY_LOWER
                || resultcode > RESPONSECODE_OKAY_UPPER) {
            L.d("OpeniAreaHttpConnect.connection():resultcode=" + String.valueOf(resultcode));
            locationFailed();
            return RESULT_NG;
        }

        // 位置情報設定
        double latitude_double;
        double longitude_double;
        try {
            latitude_double = convertLatLon(latitude_text);
            longitude_double = convertLatLon(longitude_text);
            location.setLatitude(latitude_double);
            location.setLongitude(longitude_double);
        } catch (IllegalArgumentException e) {
            locationFailed();
            return RESULT_NG;
        }
        locationSuccess(location);
        return RESULT_OK;
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
     * OpeniAreaには該当の処理は無い
     * 他と形式をそろえるため一応クチだけは残しておく
     */
    private void coldLocation(LocationManager lm){
        sendColdBroadCast(getResources().getString(R.string.categoryColdStart));
        try {
            Thread.sleep(settingDelAssistdatatime);
        } catch (InterruptedException e) {
            L.d(e.getMessage());
            e.printStackTrace();
        }

        L.d("delete_aiding_data_Stop");
        sendColdBroadCast(getResources().getString(R.string.categoryColdStop));
    }

    /**
     * 基地局から取得した緯度・経度をdouble型に変換する。
     *
     * @param str 緯度経度(文字列)
     * @return 緯度経度(double)
     * @throws IllegalArgumentException 引数不正
     */
    private double convertLatLon(final String str) throws IllegalArgumentException {
        // nullチェック
        if (str == null) {
            L.d("OpeniAreaHttpConnect.convertLatLon():input null");
            throw new IllegalArgumentException();
        }
        // フォーマットサイズチェック
        if (str.length() != XML_LATLON_FORMAT.length()) {
            L.d("OpeniAreaHttpConnect.OpeniAreaHttpConnect.convertLatLon():format NG(size)");
            L.d("OpeniAreaHttpConnect.OpeniAreaHttpConnect.convertLatLon():input=" + str);
            throw new IllegalArgumentException();
        }

        // 北緯/南緯/東経/西経 確認
        char direction = str.charAt(0);
        boolean isMinus;
        if (direction == 'N' || direction == 'E') {
            // 北緯と東経は正数
            isMinus = false;
        } else if (direction == 'S' || direction == 'W') {
            // 南緯と西経は負数
            isMinus = true;
        } else {
            L.d("OpeniAreaHttpConnect.OpeniAreaHttpConnect.convertLatLon():format NG");
            L.d("OpeniAreaHttpConnect.OpeniAreaHttpConnect.convertLatLon():input=" + str);
            throw new IllegalArgumentException();
        }

        // 度数取得
        double deg;
        try {
            deg = Double.valueOf(str.substring(1));
        } catch (NumberFormatException e) {
            L.d("OpeniAreaHttpConnect.convertLatLon():format NG");
            L.d("OpeniAreaHttpConnect.OpeniAreaHttpConnect.convertLatLon():input=" + str);
            throw new IllegalArgumentException();
        } catch (IndexOutOfBoundsException e) {
            L.d("OpeniAreaHttpConnect.convertLatLon():format NG");
            L.d("OpeniAreaHttpConnect.OpeniAreaHttpConnect.convertLatLon():input=" + str);
            throw new IllegalArgumentException();
        }
        if (isMinus == true) {
            deg = -deg;
        }
        return deg;
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
     * @param lattude 測位成功:緯度 測位失敗: -1
     * @param longitude 測位成功:経度 測位失敗: -1
     * @param ttff 測位API実行～測位停止までの時間
     */
    protected void sendLocationBroadCast(Boolean fix,Location location,double ttff){
        L.d("sendLocation");
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationiArea));
        broadcastIntent.putExtra(getResources().getString(R.string.category),getResources().getString(R.string.categoryLocation));
        broadcastIntent.putExtra(getResources().getString(R.string.TagisFix),fix);
        broadcastIntent.putExtra(getResources().getString(R.string.TagLocation),location);
        broadcastIntent.putExtra(getResources().getString(R.string.Tagttff),ttff);

        sendBroadcast(broadcastIntent);
    }

    /**
     * Cold化(アシストデータ削除)の開始と終了を通知するBroadcast
     * 削除開始:categoryColdStart 削除終了:categoryColdStop
     * @param category
     */
    protected void sendColdBroadCast(String category){
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationiArea));

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
        Intent broadcastIntent = new Intent(getResources().getString(R.string.locationiArea));
        broadcastIntent.putExtra(getResources().getString(R.string.category),getResources().getString(R.string.categoryServiceEnd));
        sendBroadcast(broadcastIntent);
    }

    protected String createQuery(){
        //リクエストボディ生成
        xmlQuery = new StringBuffer();
        xmlQuery.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
        xmlQuery.append("<DDF ver=\"1.0\">");
        xmlQuery.append("<RequestInfo>");
        xmlQuery.append("<RequestParam>");
        xmlQuery.append("<APIKey>");
        //TODO APIKeyを設定する
        xmlQuery.append("<APIKey1_ID>XXXXXXXXXX</APIKey1_ID>");
        xmlQuery.append("<APIKey2>YYYYYYYYYY</APIKey2>");
        xmlQuery.append("</APIKey>");
        xmlQuery.append("<OptionProperty>");
        xmlQuery.append("<AreaCode></AreaCode>");
        xmlQuery.append("<AreaName></AreaName>");
        xmlQuery.append("<Adr></Adr>");
        xmlQuery.append("<AdrCode></AdrCode>");
        xmlQuery.append("<PostCode></PostCode>");
        xmlQuery.append("</OptionProperty>");
        xmlQuery.append("</RequestParam>");
        xmlQuery.append("</RequestInfo>");
        xmlQuery.append("</DDF>");
        String params = xmlQuery.toString();
        return params;
    }

    public JSONObject httpResponse(String requestString){
        HttpURLConnection con;
        URL url;
        JSONObject jsonData = null;
        try {
            // URLの作成
            url = new URL(requestString);
            // 接続用HttpURLConnectionオブジェクト作成
            con = (HttpURLConnection)url.openConnection();
            // リクエストメソッドの設定
            con.setRequestMethod("POST");
            // リダイレクトを自動で許可しない設定
            con.setInstanceFollowRedirects(false);
            // URL接続からデータを読み取る場合はtrue
            con.setDoInput(true);
            // URL接続にデータを書き込む場合はtrue
            con.setDoOutput(true);
            // 接続
            con.connect();
            //レスポンス取得
            InputStream in = con.getInputStream();
            String readSt = readInputStream(in);
            byte bodyByte[] = new byte[1024];
            in.read(bodyByte);
            in.close();
            jsonData = new JSONObject(readSt).getJSONObject(resJsonObjName);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonData;
    }

    public String readInputStream(InputStream in) throws IOException, UnsupportedEncodingException {
        StringBuffer sb = new StringBuffer();
        String st = "";

        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        while((st = br.readLine()) != null) {
            sb.append(st);
        }
        try {
            in.close();
        } catch(Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
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
        return new IareaService_Binder();
    }

    @Override
    public void onRebind(Intent intent) {
    }
}