package com.nutomic.syncthingandroid.service;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.annimon.stream.Stream;
import com.nutomic.syncthingandroid.BuildConfig;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.SyncthingApp;
import com.nutomic.syncthingandroid.activities.DeviceActivity;
import com.nutomic.syncthingandroid.activities.FolderActivity;
import com.nutomic.syncthingandroid.model.CompletionInfo;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Event;
import com.nutomic.syncthingandroid.model.Folder;

import java.io.File;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/**
 * Run by the syncthing service to convert syncthing events into local broadcasts.
 *
 * It uses {@link RestApi#getEvents} to read the pending events and wait for new events.
 */
public class EventProcessor implements  Runnable, RestApi.OnReceiveEventListener {

    private static final String TAG = "EventProcessor";
    private static final String PREF_LAST_SYNC_ID = "last_sync_id";

    /**
     * Minimum interval in seconds at which the events are polled from syncthing and processed.
     * This intervall will not wake up the device to save battery power.
     */
    private static final long EVENT_UPDATE_INTERVAL = TimeUnit.SECONDS.toMillis(15);

    /**
     * Use the MainThread for all callbacks and message handling
     * or we have to track down nasty threading problems.
     */
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private volatile long mLastEventId = 0;
    private volatile boolean mShutdown = true;

    private final Context mContext;
    private final RestApi mApi;
    @Inject SharedPreferences mPreferences;
    @Inject NotificationHandler mNotificationHandler;

    public EventProcessor(Context context, RestApi api) {
        ((SyncthingApp) context.getApplicationContext()).component().inject(this);
        mContext = context;
        mApi = api;
    }

    @Override
    public void run() {
        // Restore the last event id if the event processor may have been restarted.
        if (mLastEventId == 0) {
            mLastEventId = mPreferences.getLong(PREF_LAST_SYNC_ID, 0);
        }

        // First check if the event number ran backwards.
        // If that's the case we've to start at zero because syncthing was restarted.
        mApi.getEvents(0, 1, new RestApi.OnReceiveEventListener() {
            @Override
            public void onEvent(Event event) {
            }

            @Override
            public void onDone(long lastId) {
                if (lastId < mLastEventId) mLastEventId = 0;

                Log.d(TAG, "Reading events starting with id " + mLastEventId);

                mApi.getEvents(mLastEventId, 0, EventProcessor.this);
            }
        });
    }

    /**
     * Performs the actual event handling.
     */
    @Override
    public void onEvent(Event event) {
        String deviceId;
        String folderId;

        switch (event.type) {
            case "ConfigSaved":
                if (mApi != null) {
                    Log.v(TAG, "Forwarding ConfigSaved event to RestApi to get the updated config.");
                    mApi.reloadConfig();
                }
                break;
            case "DeviceRejected":
                deviceId = (String) event.data.get("device");
                Log.d(TAG, "Unknown device " + deviceId + " wants to connect");

                Intent intent = new Intent(mContext, DeviceActivity.class)
                        .putExtra(DeviceActivity.EXTRA_IS_CREATE, true)
                        .putExtra(DeviceActivity.EXTRA_DEVICE_ID, deviceId);
                // HACK: Use a random, deterministic ID to make multiple PendingIntents
                //       distinguishable
                int requestCode = deviceId.hashCode();
                PendingIntent pi = PendingIntent.getActivity(mContext, requestCode, intent, 0);

                String title = mContext.getString(R.string.device_rejected,
                        deviceId.substring(0, 7));

                notify(title, pi);
                break;
            case "FolderCompletion":
                deviceId = (String) event.data.get("device");
                folderId = (String) event.data.get("folder");
                CompletionInfo completionInfo = new CompletionInfo();
                completionInfo.completion = (Double) event.data.get("completion");
                mApi.setCompletionInfo(deviceId, folderId, completionInfo);
                break;
            case "FolderRejected":
                deviceId = (String) event.data.get("device");
                folderId = (String) event.data.get("folder");
                String folderLabel = (String) event.data.get("folderLabel");
                Log.d(TAG, "Device " + deviceId + " wants to share folder " + folderId);

                boolean isNewFolder = Stream.of(mApi.getFolders())
                        .noneMatch(f -> f.id.equals(folderId));
                intent = new Intent(mContext, FolderActivity.class)
                        .putExtra(FolderActivity.EXTRA_IS_CREATE, isNewFolder)
                        .putExtra(FolderActivity.EXTRA_DEVICE_ID, deviceId)
                        .putExtra(FolderActivity.EXTRA_FOLDER_ID, folderId)
                        .putExtra(FolderActivity.EXTRA_FOLDER_LABEL, folderLabel);
                // HACK: Use a random, deterministic ID to make multiple PendingIntents
                //       distinguishable
                requestCode = (deviceId + folderId + folderLabel).hashCode();
                pi = PendingIntent.getActivity(mContext, requestCode, intent, 0);

                String deviceName = null;
                for (Device d : mApi.getDevices(false)) {
                    if (d.deviceID.equals(deviceId))
                        deviceName = d.getDisplayName();
                }
                title = mContext.getString(R.string.folder_rejected, deviceName,
                        folderLabel.isEmpty() ? folderId : folderLabel + " (" + folderId + ")");

                notify(title, pi);
                break;
            case "ItemFinished":
                String folder = (String) event.data.get("folder");
                String folderPath = null;
                for (Folder f : mApi.getFolders()) {
                    if (f.id.equals(folder)) {
                        folderPath = f.path;
                    }
                }
                File updatedFile = new File(folderPath, (String) event.data.get("item"));
                if (!"delete".equals(event.data.get("action"))) {
                    Log.i(TAG, "Rescanned file via MediaScanner: " + updatedFile.toString());
                    MediaScannerConnection.scanFile(mContext, new String[]{updatedFile.getPath()},
                            null, null);
                } else {
                    // https://stackoverflow.com/a/29881556/1837158
                    Log.i(TAG, "Deleted file from MediaStore: " + updatedFile.toString());
                    Uri contentUri = MediaStore.Files.getContentUri("external");
                    ContentResolver resolver = mContext.getContentResolver();
                    resolver.delete(contentUri, MediaStore.Images.ImageColumns.DATA + " LIKE ?",
                            new String[]{updatedFile.getPath()});
                }
                break;
            case "Ping":
                // Ignored.
                break;
            case "DeviceConnected":
            case "DeviceDisconnected":
            case "DeviceDiscovered":
            case "DownloadProgress":
            case "FolderPaused":
            case "FolderScanProgress":
            case "FolderSummary":
            case "ItemStarted":
            case "LocalIndexUpdated":
            case "LoginAttempt":
            case "RemoteDownloadProgress":
            case "RemoteIndexUpdated":
            case "Starting":
            case "StartupComplete":
            case "StateChanged":
                if (BuildConfig.DEBUG) {
                    Log.v(TAG, "Ignored event " + event.type + ", data " + event.data);
                }
                break;
            default:
                Log.v(TAG, "Unhandled event " + event.type);
        }
    }

    @Override
    public void onDone(long id) {
        if (mLastEventId < id) {
            mLastEventId = id;

            // Store the last EventId in case we get killed
            mPreferences.edit().putLong(PREF_LAST_SYNC_ID, mLastEventId).apply();
        }

        synchronized (mMainThreadHandler) {
            if (!mShutdown) {
                mMainThreadHandler.removeCallbacks(this);
                mMainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL);
            }
        }
    }

    public void start() {
        Log.d(TAG, "Starting event processor.");

        // Remove all pending callbacks and add a new one. This makes sure that only one
        // event poller is running at any given time.
        synchronized (mMainThreadHandler) {
            mShutdown = false;
            mMainThreadHandler.removeCallbacks(this);
            mMainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL);
        }
    }

    public void stop() {
        Log.d(TAG, "Stopping event processor.");
        synchronized (mMainThreadHandler) {
            mShutdown = true;
            mMainThreadHandler.removeCallbacks(this);
        }
    }

    private void notify(String text, PendingIntent pi) {
        // HACK: Use a random, deterministic ID between 1000 and 2000 to avoid duplicate
        //       notifications.
        int notificationId = 1000 + text.hashCode() % 1000;
        mNotificationHandler.showEventNotification(text, pi, notificationId);
    }
}
