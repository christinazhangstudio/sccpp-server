package com.cz.sccppserver;

import android.app.Service;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.IBinder;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class Server extends Service {
  private static final String TAG = "SccppServer";
  private static final String SOCKET_NAME = "sccpp";

  @Override
  public IBinder onBind(Intent intent) { return null; }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    new Thread(this::runServer).start();
    return START_STICKY;
  }

  private void runServer() {
    LocalServerSocket serverSocket = null;
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
      Log.i(TAG, "send device name: " + deviceName);

      // send fake H.264 stream for now
      sendFakeVideo(out);
    } catch (Exception e) {
      Log.e(TAG, "client error", e);
    } finally {
      try { client.close(); } catch (Exception ignored) {}
    }
  }

  private void sendFakeVideo(DataOutputStream out) throws IOException {
    byte[] blueIframe = new byte[] {
      // simplified NAL unit
      0x00, 0x00, 0x00, 0x01,  // start code
      0x67, // SPS (fake)
      0x00, 0x00, 0x00, 0x01,
      0x68, // PPS
      0x00, 0x00, 0x00, 0x01,
      0x65, // IDR (blue pixel)
      // ... real data would be longer
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