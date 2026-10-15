package br.com.originalmeathouse.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Abre o player automaticamente quando o TV box liga. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launch);
    }
}
