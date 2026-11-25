import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

// Tiny helper that holds transient-may-duck audio focus while the assistant is
// active. Other players (Bluetooth A2DP sink, Yandex Music, etc.) respond by
// lowering their own volume, independently of the daemon's TTS output.
public class FocusHelper {
    public static void main(String[] args) {
        String flagPath = args.length > 0 ? args[0] : "/data/local/tmp/assistant_duck";
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();

            // Publish our real PID; the daemon uses it as a liveness check.
            try (FileOutputStream pf = new FileOutputStream("/data/local/openstation/focus_helper.pid")) {
                pf.write((Integer.toString(android.os.Process.myPid()) + "\n").getBytes());
            }

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object at = systemMain.invoke(null);
            Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context ctx = (Context) getSystemContext.invoke(at);

            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                System.out.println("ERROR: no AudioManager");
                return;
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFocusRequest request = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int change) {
                            System.out.println("focus change=" + change);
                        }
                    }, new Handler(Looper.getMainLooper()))
                    .build();

            System.out.println("FocusHelper started flag=" + flagPath);
            boolean focused = false;
            long lastCheck = 0;
            File flag = new File(flagPath);
            while (true) {
                long now = System.currentTimeMillis();
                if (now - lastCheck < 50) {
                    Thread.sleep(50);
                    continue;
                }
                lastCheck = now;
                boolean want = readFlag(flag);
                if (want && !focused) {
                    int r = am.requestAudioFocus(request);
                    System.out.println("requestAudioFocus -> " + r);
                    focused = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                } else if (!want && focused) {
                    am.abandonAudioFocusRequest(request);
                    System.out.println("abandonAudioFocus");
                    focused = false;
                }
            }
        } catch (Throwable t) {
            System.out.println("ERROR: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static boolean readFlag(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            int c = in.read();
            return c == '1' || c == 't' || c == 'T' || c == 'y' || c == 'Y';
        } catch (Throwable t) {
            return false;
        }
    }
}
