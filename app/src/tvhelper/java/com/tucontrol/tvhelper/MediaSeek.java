package com.tucontrol.tvhelper;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Runs on the TV through ADB ({@code app_process}, as the shell user) and moves the video that
 * is playing to an exact time, then makes sure it keeps playing. The TV's remote protocol only
 * has fast-forward/rewind keys, which jump by fixed steps; media sessions accept an exact time.
 *
 * <pre>
 * MediaSeek to  &lt;ms&gt;   jump to that time
 * MediaSeek by  &lt;ms&gt;   jump forwards (positive) or backwards (negative)
 * MediaSeek pos          print the current time
 * </pre>
 * Prints {@code ok <package> <fromMs> <toMs> <durationMs>} or {@code error <reason>}.
 */
public final class MediaSeek {

    private MediaSeek() {}

    public static void main(String[] args) {
        try {
            System.out.println(run(args));
            System.exit(0);
        } catch (Throwable error) {
            System.out.println("error " + error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }

    private static String run(String[] args) throws Exception {
        if (args.length < 1) return "error usage";
        Looper.prepareMainLooper();
        registerMediaServices();
        Context context = systemContext();
        MediaSessionManager sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        MediaController controller = pick(sessions.getActiveSessions(null));
        if (controller == null) return "error no-media";

        PlaybackState state = controller.getPlaybackState();
        long now = position(state);
        long duration = 0;
        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        if (args[0].equals("pos")) return "ok " + controller.getPackageName() + " " + now + " " + now + " " + duration;
        if (args.length < 2) return "error usage";

        long value = Long.parseLong(args[1]);
        long target = args[0].equals("by") ? now + value : value;
        if (target < 0) target = 0;
        if (duration > 0 && target > duration - 1_000) target = Math.max(0, duration - 1_000);

        MediaController.TransportControls controls = controller.getTransportControls();
        controls.seekTo(target);
        Thread.sleep(400);
        controls.play(); // Never leave the video paused after jumping.
        return "ok " + controller.getPackageName() + " " + now + " " + target + " " + duration;
    }

    /** The session playing now, else a paused one, else whichever is first. */
    private static MediaController pick(List<MediaController> controllers) {
        MediaController paused = null;
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state == null) continue;
            if (state.getState() == PlaybackState.STATE_PLAYING) return controller;
            if (paused == null && state.getState() == PlaybackState.STATE_PAUSED) paused = controller;
        }
        if (paused != null) return paused;
        return controllers.isEmpty() ? null : controllers.get(0);
    }

    /** The reported position, advanced by the time elapsed since it was reported while playing. */
    private static long position(PlaybackState state) {
        if (state == null) return 0;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            position += (long) (elapsed * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    /**
     * Android 13+ wires the media services up while starting an app; a bare app_process has
     * to do it itself or MediaSessionManager cannot reach its service. Older versions skip it.
     */
    private static void registerMediaServices() {
        Object services;
        Class<?> manager;
        try {
            manager = Class.forName("android.media.MediaServiceManager");
            Constructor<?> constructor = manager.getDeclaredConstructor();
            constructor.setAccessible(true);
            services = constructor.newInstance();
        } catch (Throwable notNeeded) {
            return; // Android 12 and older reach the service directly.
        }
        // MediaSessionManager reads it from the platform initializer; the media module keeps its own.
        for (String initializer : new String[] {
            "android.media.MediaFrameworkPlatformInitializer", "android.media.MediaFrameworkInitializer",
        }) {
            try {
                java.lang.reflect.Method set = Class.forName(initializer).getDeclaredMethod("setMediaServiceManager", manager);
                set.setAccessible(true);
                set.invoke(null, services);
            } catch (Throwable ignored) {
                // Not present (or already set) on this Android version.
            }
        }
    }

    /** A system context for a plain process started with app_process (no app around it). */
    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = activityThread.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Field current = activityThread.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }
}
