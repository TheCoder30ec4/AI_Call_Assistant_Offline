package app.offlinevoice.callprobe;

import android.os.ParcelFileDescriptor;

/** Runs as the shell user through Shizuku. No generic command execution is exposed. */
interface IShellProbe {
    /** Shizuku's teardown hook; the code is fixed by Shizuku. */
    void destroy() = 16777114;

    int getUid() = 1;

    /** Allows this app to see calls. Returns the command result. */
    String setup() = 2;

    /** Reverses setup(). */
    String undoSetup() = 3;

    /** JSON: permissions and whether the platform reports call audio as interceptable. */
    String capabilities() = 4;

    /** Opens call audio. method: 0 auto, 1 redirection, 2 direct. Returns a JSON status. */
    String open(int sampleRate, int method) = 5;

    /** JSON audio-path status: method, routes, frame counts, timing. Never contains audio. */
    String status() = 6;

    void close() = 7;

    /** Queues 16 kHz mono PCM16 to play to the caller after anything already queued. */
    void playSpeech(in byte[] pcm16k) = 9;

    /** Returns a pipe carrying the caller's audio as mono PCM16 at the open sample rate. */
    ParcelFileDescriptor openCallerStream() = 12;

    /** True while queued speech is still playing to the caller. */
    boolean isSpeaking() = 13;

    /** Records both sides of the call until it ends: caller on the left channel, AI on the right. */
    void recordCall(in ParcelFileDescriptor file) = 14;

    /** Ends the current call with the system end-call key. Backup for when Call.disconnect() is ignored. */
    String endCall() = 15;

    /** Model files waiting in /data/local/tmp/ai-call-assistant/models (from push_models.sh), one relative path per line. */
    String listModelImport() = 16;

    /** Opens one of those files read-only, so the app can copy it into its private storage. */
    ParcelFileDescriptor openModelImport(String path) = 17;

    /** Deletes the waiting copies once the app has imported them. */
    void clearModelImport() = 18;
}
