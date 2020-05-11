package jp.sio.testapp.mylocation.Repository;

import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;

import jp.sio.testapp.mylocation.L;

/**
 * Logファイルに関するクラス
 * Created by NTT docomo on 2017/05/22.
 */

public class LocationLog {
    private long createLogTime;
    private SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private String LINEFEED = "\r\n";
    private File file;
    private String fileName;
    private String filePath;
    private File dirPath;
    private OutputStreamWriter outputStreamWriter;
    private FileOutputStream fileOutputStream;

    private Context context;

    public LocationLog(Context context){
        this.context = context;
    }

    /**
     * Logファイルを作成
     */
    public void makeLogFile(String settingHeader){
        createLogTime = System.currentTimeMillis();
        fileName = simpleDateFormat.format(createLogTime) + ".txt";
        if(isExternalStrageWriteable()){
            L.d("ExternalStrage書き込みOK");
            //dirPath = Environment.getExternalStorageDirectory().getPath() + "/MyLocation/";
            dirPath = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS + "/MyLocation");

            L.d(dirPath.toString());
            filePath = dirPath + "/" + fileName;
            file = new File(filePath);
            try {
                if(!file.getParentFile().exists()){
                    file.getParentFile().mkdir();
                }
                if(file.exists()){
                    file.createNewFile();
                }
                fileOutputStream = new FileOutputStream(file,true);

            } catch (IOException e) {
                e.printStackTrace();
            }

        }else{
            //ExternalStrage(/sdcard配下)に書き込めない
            L.d("ExternalStrage書き込み不可");
            try {
                fileOutputStream = context.openFileOutput(fileName,Context.MODE_APPEND);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        try {
            outputStreamWriter = new OutputStreamWriter(fileOutputStream, "UTF-8");
            L.d("settingHeader:" + settingHeader);
            outputStreamWriter.append(settingHeader + LINEFEED);
            outputStreamWriter.flush();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }


        L.d("LogFilePath:" + filePath);

    }

    /**
     * Logファイルへの書き込み
     */
    public void writeLog(String log){
        try {
            L.d("Log:" + log);
            outputStreamWriter.write(log + LINEFEED);
            outputStreamWriter.flush();
            //outputStreamWriter.newLine();

        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * Logファイルを閉じる(Readerとかを閉じる処理を想定)
     *
     */
    public void endLogFile(){
            scanFile();
        try {
            outputStreamWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * ログファイルを端末再起動無しでも読み込むための処理
     * ファイルインデックスを作成しなおせば良いと見たのでそれを実装
     */
    public void scanFile() {
        if(isExternalStrageWriteable()) {
            Uri contentUri = Uri.fromFile(file);
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, contentUri);
            context.sendBroadcast(mediaScanIntent);
        }
    }

    //externalStrageのReadとWriteが可能かチェック
    private boolean isExternalStrageWriteable(){
        boolean result = false;
        String state = Environment.getExternalStorageState();
        if(Environment.MEDIA_MOUNTED.equals(state)){
            result = true;
        }
        L.d("isExternalStrageWriteable:"+result);
        return result;
    }
}