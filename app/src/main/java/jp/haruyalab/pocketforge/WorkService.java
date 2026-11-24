package jp.haruyalab.pocketforge;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Keeps an explicit, user-started network task alive while the browser is in front. */
public final class WorkService extends Service {
    private static final String CHANNEL="work";
    @Override public void onCreate(){ super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"AI・同期処理",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        Intent launch=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,1,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PocketForge").setContentText("ログイン・AI・同期処理を実行中").setContentIntent(pi).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29) startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(7,n);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTimeout(int startId,int fgsType){stopSelf();}
}
