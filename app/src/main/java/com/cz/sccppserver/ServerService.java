package com.cz.sccppserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.IBinder;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.Surface;
import android.util.DisplayMetrics;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ServerService extends Service {
  private static final String TAG = "SccppServerService";
  private static final String SOCKET_NAME = "sccpp";

  // activity-related
  private static final String CHANNEL_ID = "scrcpy_channel";
  private static final int NOTIFICATION_ID = 1;

  private MediaProjectionManager projectionManager;
  private MediaProjection projection;
  private LocalServerSocket serverSocket;

  @Override
  public IBinder onBind(Intent intent) { return null; }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    createNotificationChannel();
    startForeground(NOTIFICATION_ID, createNotification());

    projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    int resultCode = intent.getIntExtra("resultCode", android.app.Activity.RESULT_CANCELED);
    Intent data = intent.getParcelableExtra("data");

    if (resultCode != android.app.Activity.RESULT_OK || data == null) {
      Log.e(TAG, "Invalid resultCode or data");
      stopSelf();
      return START_NOT_STICKY;
    }

    projection = projectionManager.getMediaProjection(resultCode, data);
    if (projection == null) {
      Log.e(TAG, "Failed to get MediaProjection");
      stopSelf();
      return START_NOT_STICKY;
    }

    projection.registerCallback(new MediaProjection.Callback() {
      @Override
      public void onStop() {
        Log.i(TAG, "MediaProjection stopped");
        stopSelf();
      }
    }, new Handler(Looper.getMainLooper()));

    new Thread(this::runServer).start();
    return START_NOT_STICKY; // prevents Android from restarting service without projection data ?
  }

  private void runServer() {
    try {
      serverSocket = new LocalServerSocket(SOCKET_NAME);
      Log.i(TAG, "local server listening on localabstract:sccpp");

      // adb forward
      while(true) {
        LocalSocket client = serverSocket.accept();
        new Thread(() -> handleClient(client)).start();
      }
    } catch (Exception e) {
      Log.e(TAG, "server error", e);
    } finally {
      if (serverSocket != null) try { serverSocket.close(); } catch (Exception ignored) {}
    }
  }

  private void handleClient(LocalSocket client) {
    try(
      DataInputStream in = new DataInputStream(client.getInputStream());
      DataOutputStream out = new DataOutputStream(client.getOutputStream())) 
    {
      // read the "sccpp" handshake
      byte[] handshake = new byte[5];
      in.readFully(handshake);
      if(!new String(handshake).equals("sccpp")) {
        Log.e(TAG, "invalid handshake; got: " + new String(handshake));
        return;
      }
      Log.i(TAG, "handshake succeeded");

      // send device name
      String deviceName = android.os.Build.MODEL;
      out.write(deviceName.getBytes(StandardCharsets.UTF_8));
      out.write(0); // null term
      Log.i(TAG, "sent device name: " + deviceName);

      printSpecs();

      // Log.i(TAG, "sending fake video");
      // sendFakeVideo(out);
      // Log.i(TAG, "finished sending fake video");
      Log.i(TAG, "sending real video");
      startScreenCapture(out);
      Log.i(TAG, "finished sending real video");
    } catch (Exception e) {
      Log.e(TAG, "client error", e);
    } finally {
      try { client.close(); } catch (Exception ignored) {}
    }
  }

  private void printSpecs() {
    try {
      MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");

      // Get codec info from the encoder instance
      MediaCodecInfo codecInfo = encoder.getCodecInfo();
      MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");

      Log.i(TAG, "encoder name: " + codecInfo.getName());

      // print profile/levels
      MediaCodecInfo.CodecProfileLevel[] pls = caps.profileLevels;
      if (pls != null && pls.length > 0) {
        for (MediaCodecInfo.CodecProfileLevel pl : pls) {
          Log.i(TAG, "profile=" + pl.profile + " level=" + pl.level);
        }
      } else {
        Log.i(TAG, "no profileLevels reported");
      }

      // print color formats
      int[] cfs = caps.colorFormats;
      if (cfs != null && cfs.length > 0) {
        for (int cf : cfs) {
          Log.i(TAG, "colorFormat=" + cf);
        }
      } else {
        Log.i(TAG, "no colorFormats reported");
      }

      // IMPORTANT: release encoder if you won't use it now (we only wanted info)
      encoder.release();

    } catch (IOException e) {
      Log.e(TAG, "failed to create encoder", e);
    }
  }

  private void startScreenCapture(DataOutputStream out) {
    try {
      DisplayMetrics metrics = new DisplayMetrics();
      WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
      wm.getDefaultDisplay().getMetrics(metrics);
      int rawWidth = metrics.widthPixels;
      int rawHeight = metrics.heightPixels;
      int dpi = metrics.densityDpi;

      // Samsung Exynos encoders require width and height
      // to be multiples of 16
      int width = (rawWidth + 15) & ~15;
      int height = (rawHeight + 15) & ~15;

      // MediaCodec encoder
      MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
      format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
              android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

      // required for Exynos H.264 encoder:
      format.setInteger(MediaFormat.KEY_PROFILE,
              MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      format.setInteger(MediaFormat.KEY_LEVEL,
              MediaCodecInfo.CodecProfileLevel.AVCLevel31);

      format.setInteger(MediaFormat.KEY_BITRATE_MODE,
              MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

      try {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      } catch (IllegalArgumentException e) {
        throw new Exception("configure failed: " + e);
      }

      Surface inputSurface = encoder.createInputSurface();
      encoder.start();

      // "Android requires VirtualDisplay creation on a Looper-attached thread,
      // the main thread. Otherwise, MediaProjection sometimes thinks you’re
      // using a “non-current” projection."
      //      projection.createVirtualDisplay(
      //              "sccpp_encoder",
      //              width, height, dpi,
      //              DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
      //              inputSurface,
      //              null, null
      //      );
      new Handler(Looper.getMainLooper()).post(() -> {
        projection.createVirtualDisplay(
                "sccpp_encoder",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                inputSurface,
                null, null
        );
      });

      Log.i(TAG, "encoder & VirtualDisplay started");

      android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
      boolean first = true;

      while(true) {
        int outIndex = encoder.dequeueOutputBuffer(info, 1000);
        if(outIndex >= 0) {
          java.nio.ByteBuffer buf = encoder.getOutputBuffer(outIndex);
          if(buf == null) {
            throw new Exception("buf nil");
          }
          byte[] nal = new byte[info.size];
          buf.get(nal);
          buf.position(info.offset);
          buf.limit(info.offset + info.size);

          out.writeLong(System.nanoTime() / 1000);
          out.writeInt(nal.length);
          out.write(nal);
          out.flush();

          if(first) {
            Log.i(TAG, "first real frame sent: " + nal.length + "bytes");
            first = false;
          }

          encoder.releaseOutputBuffer(outIndex, false);
        }

        if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          break;
        }
      }

      encoder.stop();
      encoder.release();
    } catch (Exception e) {
      Log.e(TAG, "screen capture error", e);
    }
  }

  private void createNotificationChannel() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Scrcpy Service", NotificationManager.IMPORTANCE_LOW);
      NotificationManager manager = getSystemService(NotificationManager.class);
      manager.createNotificationChannel(channel);
    }
  }

  private Notification createNotification() {
    return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Scrcpy Server")
            .setContentText("Capturing screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (projection != null) projection.stop();
    if (serverSocket != null) {
      try { serverSocket.close(); } catch (Exception ignored) {}
    }
  }

  private void sendFakeVideo(DataOutputStream out) throws IOException {
    // will throw:
    // [INFO]: received NAL; 15 bytes
    //[h264 @ 00000278ae3b8940] Overread SPS by 8 bits
    //[h264 @ 00000278ae3b8940] sps_id 32 out of range
    //[h264 @ 00000278ae3b8940] pps_id 807 out of range
    //[h264 @ 00000278ae3b8940] slice type 32 too large at -1
    // see below for slightly more valid test data...
    //     byte[] blueIframe = new byte[] {
    //       // simplified NAL unit
    //       0x00, 0x00, 0x00, 0x01,
    //       0x67, // SPS (fake)
    //       0x00, 0x00, 0x00, 0x01,
    //       0x68, // PPS
    //       0x00, 0x00, 0x00, 0x01,
    //       0x65, // IDR (blue pixel)
    //       // ... real data would be longer
    //     };

    // can be generated with:
    // ffmpeg -f lavfi -i color=blue:size=2x2 -frames:v 1 -g 1 -vcodec libx264 -x264opts keyint=1:min-keyint=1 -preset ultrafast -f h264 test.h26
    // xxd -i test.h264 (in Git bash if xxd isn't installed)
    // this should be decodable...
    // [INFO]: received NAL; 652 bytes
    // [INFO]: DECODED FRAME: 2x2, format=0
    // [INFO]: first frame decoded
    byte[] blueIframe = new byte[] {
      0x00, 0x00, 0x00, 0x01, 0x67, 0x42, (byte)0xc0, 0x0a, (byte)0xdd, (byte)0xf8, (byte)0x88, (byte)0x8c,
      0x04, 0x40, 0x00, 0x00, 0x03, 0x00, 0x40, 0x00, 0x00, 0x0c, (byte)0x83, (byte)0xc4,
          (byte)0x89, (byte)0xe0, 0x00, 0x00, 0x00, 0x01, 0x68, (byte)0xce, 0x0f, (byte)0xc8, 0x00, 0x00,
      0x01, 0x06, 0x05, (byte)0xff, (byte)0xff, 0x4d, (byte)0xdc, 0x45, (byte)0xe9, (byte)0xbd, (byte)0xe6, (byte)0xd9,
      0x48, (byte)0xb7, (byte)0x96, 0x2c, (byte)0xd8, 0x20, (byte)0xd9, 0x23, (byte)0xee, (byte)0xef, 0x78, 0x32,
      0x36, 0x34, 0x20, 0x2d, 0x20, 0x63, 0x6f, 0x72, 0x65, 0x20, 0x31, 0x36,
      0x35, 0x20, 0x72, 0x33, 0x32, 0x32, 0x32, 0x20, 0x62, 0x33, 0x35, 0x36,
      0x30, 0x35, 0x61, 0x20, 0x2d, 0x20, 0x48, 0x2e, 0x32, 0x36, 0x34, 0x2f,
      0x4d, 0x50, 0x45, 0x47, 0x2d, 0x34, 0x20, 0x41, 0x56, 0x43, 0x20, 0x63,
      0x6f, 0x64, 0x65, 0x63, 0x20, 0x2d, 0x20, 0x43, 0x6f, 0x70, 0x79, 0x6c,
      0x65, 0x66, 0x74, 0x20, 0x32, 0x30, 0x30, 0x33, 0x2d, 0x32, 0x30, 0x32,
      0x35, 0x20, 0x2d, 0x20, 0x68, 0x74, 0x74, 0x70, 0x3a, 0x2f, 0x2f, 0x77,
      0x77, 0x77, 0x2e, 0x76, 0x69, 0x64, 0x65, 0x6f, 0x6c, 0x61, 0x6e, 0x2e,
      0x6f, 0x72, 0x67, 0x2f, 0x78, 0x32, 0x36, 0x34, 0x2e, 0x68, 0x74, 0x6d,
      0x6c, 0x20, 0x2d, 0x20, 0x6f, 0x70, 0x74, 0x69, 0x6f, 0x6e, 0x73, 0x3a,
      0x20, 0x63, 0x61, 0x62, 0x61, 0x63, 0x3d, 0x30, 0x20, 0x72, 0x65, 0x66,
      0x3d, 0x31, 0x20, 0x64, 0x65, 0x62, 0x6c, 0x6f, 0x63, 0x6b, 0x3d, 0x30,
      0x3a, 0x30, 0x3a, 0x30, 0x20, 0x61, 0x6e, 0x61, 0x6c, 0x79, 0x73, 0x65,
      0x3d, 0x30, 0x3a, 0x30, 0x20, 0x6d, 0x65, 0x3d, 0x64, 0x69, 0x61, 0x20,
      0x73, 0x75, 0x62, 0x6d, 0x65, 0x3d, 0x30, 0x20, 0x70, 0x73, 0x79, 0x3d,
      0x31, 0x20, 0x70, 0x73, 0x79, 0x5f, 0x72, 0x64, 0x3d, 0x31, 0x2e, 0x30,
      0x30, 0x3a, 0x30, 0x2e, 0x30, 0x30, 0x20, 0x6d, 0x69, 0x78, 0x65, 0x64,
      0x5f, 0x72, 0x65, 0x66, 0x3d, 0x30, 0x20, 0x6d, 0x65, 0x5f, 0x72, 0x61,
      0x6e, 0x67, 0x65, 0x3d, 0x31, 0x36, 0x20, 0x63, 0x68, 0x72, 0x6f, 0x6d,
      0x61, 0x5f, 0x6d, 0x65, 0x3d, 0x31, 0x20, 0x74, 0x72, 0x65, 0x6c, 0x6c,
      0x69, 0x73, 0x3d, 0x30, 0x20, 0x38, 0x78, 0x38, 0x64, 0x63, 0x74, 0x3d,
      0x30, 0x20, 0x63, 0x71, 0x6d, 0x3d, 0x30, 0x20, 0x64, 0x65, 0x61, 0x64,
      0x7a, 0x6f, 0x6e, 0x65, 0x3d, 0x32, 0x31, 0x2c, 0x31, 0x31, 0x20, 0x66,
      0x61, 0x73, 0x74, 0x5f, 0x70, 0x73, 0x6b, 0x69, 0x70, 0x3d, 0x31, 0x20,
      0x63, 0x68, 0x72, 0x6f, 0x6d, 0x61, 0x5f, 0x71, 0x70, 0x5f, 0x6f, 0x66,
      0x66, 0x73, 0x65, 0x74, 0x3d, 0x30, 0x20, 0x74, 0x68, 0x72, 0x65, 0x61,
      0x64, 0x73, 0x3d, 0x31, 0x20, 0x6c, 0x6f, 0x6f, 0x6b, 0x61, 0x68, 0x65,
      0x61, 0x64, 0x5f, 0x74, 0x68, 0x72, 0x65, 0x61, 0x64, 0x73, 0x3d, 0x31,
      0x20, 0x73, 0x6c, 0x69, 0x63, 0x65, 0x64, 0x5f, 0x74, 0x68, 0x72, 0x65,
      0x61, 0x64, 0x73, 0x3d, 0x30, 0x20, 0x6e, 0x72, 0x3d, 0x30, 0x20, 0x64,
      0x65, 0x63, 0x69, 0x6d, 0x61, 0x74, 0x65, 0x3d, 0x31, 0x20, 0x69, 0x6e,
      0x74, 0x65, 0x72, 0x6c, 0x61, 0x63, 0x65, 0x64, 0x3d, 0x30, 0x20, 0x62,
      0x6c, 0x75, 0x72, 0x61, 0x79, 0x5f, 0x63, 0x6f, 0x6d, 0x70, 0x61, 0x74,
      0x3d, 0x30, 0x20, 0x63, 0x6f, 0x6e, 0x73, 0x74, 0x72, 0x61, 0x69, 0x6e,
      0x65, 0x64, 0x5f, 0x69, 0x6e, 0x74, 0x72, 0x61, 0x3d, 0x30, 0x20, 0x62,
      0x66, 0x72, 0x61, 0x6d, 0x65, 0x73, 0x3d, 0x30, 0x20, 0x77, 0x65, 0x69,
      0x67, 0x68, 0x74, 0x70, 0x3d, 0x30, 0x20, 0x6b, 0x65, 0x79, 0x69, 0x6e,
      0x74, 0x3d, 0x31, 0x20, 0x6b, 0x65, 0x79, 0x69, 0x6e, 0x74, 0x5f, 0x6d,
      0x69, 0x6e, 0x3d, 0x31, 0x20, 0x73, 0x63, 0x65, 0x6e, 0x65, 0x63, 0x75,
      0x74, 0x3d, 0x30, 0x20, 0x69, 0x6e, 0x74, 0x72, 0x61, 0x5f, 0x72, 0x65,
      0x66, 0x72, 0x65, 0x73, 0x68, 0x3d, 0x30, 0x20, 0x72, 0x63, 0x3d, 0x63,
      0x72, 0x66, 0x20, 0x6d, 0x62, 0x74, 0x72, 0x65, 0x65, 0x3d, 0x30, 0x20,
      0x63, 0x72, 0x66, 0x3d, 0x32, 0x33, 0x2e, 0x30, 0x20, 0x71, 0x63, 0x6f,
      0x6d, 0x70, 0x3d, 0x30, 0x2e, 0x36, 0x30, 0x20, 0x71, 0x70, 0x6d, 0x69,
      0x6e, 0x3d, 0x30, 0x20, 0x71, 0x70, 0x6d, 0x61, 0x78, 0x3d, 0x36, 0x39,
      0x20, 0x71, 0x70, 0x73, 0x74, 0x65, 0x70, 0x3d, 0x34, 0x20, 0x69, 0x70,
      0x5f, 0x72, 0x61, 0x74, 0x69, 0x6f, 0x3d, 0x31, 0x2e, 0x34, 0x30, 0x20,
      0x61, 0x71, 0x3d, 0x30, 0x00, (byte)0x80, 0x00, 0x00, 0x01, 0x65, (byte)0x88, (byte)0x84,
      0x3a, 0x11, (byte)0x8a, 0x00, 0x02, 0x31, 0x71, (byte)0xc0, 0x00, 0x43, (byte)0xca, 0x38,
      0x00, 0x08, 0x05, (byte)0xe0,
    };

    // send 100 frames
    for(int i = 0; i < 100; i++) {
      out.writeLong(i * 1000000); // timestamp (us)
      out.writeInt(blueIframe.length);
      out.write(blueIframe);
      try {
        Thread.sleep(40);
      } catch (Exception ignored) {}
    }
  }
}