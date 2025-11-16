package com.cz.sccppserver;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class MainActivity extends Activity {
  private static final String TAG = "SccppMainActivity";
  private static final int REQUEST_CODE = 1000;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    Intent intent = mgr.createScreenCaptureIntent();
    startActivityForResult(intent, REQUEST_CODE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_CODE) {
      if (resultCode == RESULT_OK) {
        Log.i(TAG, "Permission granted! Starting foreground service.");
        // Intent serviceIntent = new Intent(this, ServerService.class);
        // serviceIntent.putExtra("resultCode", resultCode);
        // serviceIntent.putExtra("data", data);

        // clone data to avoid it becoming stale via modification
        Intent dataCopy = new Intent(data);
        dataCopy.setClipData(data.getClipData());
        dataCopy.addFlags(data.getFlags());

        Intent serviceIntent = new Intent(this, ServerService.class);
        serviceIntent.putExtra("resultCode", resultCode);
        serviceIntent.putExtra("data", dataCopy);
        serviceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        startForegroundService(serviceIntent);
      } else {
        Log.e(TAG, "Permission denied by user.");
      }
      //finish();  // Close activity
      // make sure the Activity does NOT finish before the service starts
      new Handler(Looper.getMainLooper()).postDelayed(this::finish, 150);
    }
  }
}