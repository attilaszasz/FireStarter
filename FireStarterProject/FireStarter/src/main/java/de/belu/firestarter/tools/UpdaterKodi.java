package de.belu.firestarter.tools;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

/**
 * Created by attila.szasz on 22-Oct-15.
 */
public class UpdaterKodi {

    /** Latest version found on UPDATEURL */
    public static String LATEST_VERSION = null;

    /** Update url on github */
    //private final static String UPDATEURL = "http://mirrors.kodi.tv/snapshots/android/arm/";
    private final static String UPDATEURL = "http://mirrors.kodi.tv/releases/android/arm/";

    /** Update dir on external storage */
    private final static String DOWNLOADFOLDER = "FireStarterInstalls";

    /** Indicates if process is busy */
    private static Boolean mIsBusy = false;

    /** Name of APK **/
    private String mApkName = null;

    /** Url of APK **/
    private String mApkURL = null;

    /** Update semaphore */
    private Semaphore mUpdateSemaphore = null;

    /** Indicates if the download was succesful */
    private Boolean mDownloadSuccessful = false;

    /** Error reason */
    private String mDownloadErrorReason = null;

    /** Queue value of running download */
    private Long mQueueValue;

    /** Download manager */
    private DownloadManager mDownloadManager;

    /** Check for update listener */
    private OnCheckForUpdateFinishedListener mOnCheckForUpdateFinishedListener;

    /** Update progress listener */
    private OnUpdateProgressListener mOnUpdateProgressListener;

    /** Set the check for update listener */
    public void setOnCheckForUpdateFinishedListener(OnCheckForUpdateFinishedListener listener)
    {
        mOnCheckForUpdateFinishedListener = listener;
    }

    /** Set progress update listener */
    public void setOnUpdateProgressListener(OnUpdateProgressListener listener)
    {
        mOnUpdateProgressListener = listener;
    }

    /** Check if version is higher */
    public static Boolean isVersionNewer(String oldVersion, String newVersion)
    {
        // for now, just check if they are different and treat it like it's newer
        return !oldVersion.equals(newVersion);
    }

    /**
     * Get version from file name
     * @param apkName file name to be parsed
     * @return Version string
     */
    private static String getVersion(String apkName)
    {
        String retVal = null;

        try
        {
            if(apkName != null && !apkName.equals(""))
            {
                apkName = apkName
                            .replace("kodi-", "")
                            .replace("-Isengard", "")
                            .replace("-Jarvis", "")
                            .replace("_rc", "-RC")
                            .replace("_alpha", "-ALPHA")
                            .replace("-armeabi-v7a.apk", "");
                retVal = "v" + apkName;
            }
        }
        catch(Exception ignore) { }

        return retVal;
    }

    /** Check for update and the update Kodi */
    public void updateKodi(final Context context, final String oldVersion)
    {
        Thread updateThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (mIsBusy)
                    {
                        throw new Exception("Updater is already working..");
                    }

                    // Check for update synchron
                    checkForUpdate(true);
                    mIsBusy = true;

                    // Check if update-check was successful and version is newer
                    if (LATEST_VERSION == null || !isVersionNewer(oldVersion, LATEST_VERSION))
                    {
                        throw new Exception("No newer version found..");
                    }
                    if(mApkURL == null)
                    {
                        throw new Exception("Download URL of new version not found..");
                    }
                    fireOnUpdateProgressListener(false, 10, "Newer version found, start download..");

                    // Create download-dir and start download
                    File downloadDir = new File(Environment.getExternalStorageDirectory(), DOWNLOADFOLDER);

                    context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://" + downloadDir.getAbsolutePath())));

                    if(downloadDir.exists() && !downloadDir.isDirectory())
                    {
                        if(!downloadDir.delete())
                        {
                            throw new Exception("Can not delete file: " + downloadDir.getAbsolutePath());
                        }
                    }
                    if(!downloadDir.exists() && !downloadDir.mkdir())
                    {
                        throw new Exception("Can not create download folder: " + downloadDir.getAbsolutePath());
                    }
                    else
                    {
                        Tools.deleteDirectoryRecursively(context, downloadDir, true);
                    }

                    context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://" + downloadDir.getAbsolutePath())));

                    File downloadFile = new File(downloadDir, "FireStarter-" + LATEST_VERSION + ".apk");

                    mDownloadSuccessful = false;
                    mDownloadErrorReason = null;
                    DownloadManager.Request localRequest = new DownloadManager.Request(Uri.parse(mApkURL));
                    localRequest.setDescription("Downloading FireStarter " + LATEST_VERSION);
                    localRequest.setTitle("FireStarter Update");
                    localRequest.allowScanningByMediaScanner();
                    localRequest.setNotificationVisibility(1);
                    Log.d(Updater.class.getName(), "Download to file://" + downloadFile.getAbsolutePath());
                    localRequest.setDestinationUri(Uri.parse("file://" + downloadFile.getAbsolutePath()));

                    context.registerReceiver(new BroadcastReceiver()
                    {
                        public void onReceive(Context context, Intent intent)
                        {
                            String action = intent.getAction();
                            Log.d(Updater.class.getName(), "Received intent: " + action);
                            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action))
                            {
                                DownloadManager.Query query = new DownloadManager.Query();
                                query.setFilterById(mQueueValue);
                                Cursor c = mDownloadManager.query(query);
                                if (c.moveToFirst())
                                {
                                    int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                                    if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex))
                                    {
                                        mDownloadSuccessful = true;
                                    }
                                    else
                                    {
                                        // Try to get error reason
                                        switch(c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON)))
                                        {
                                            case DownloadManager.ERROR_CANNOT_RESUME:
                                                mDownloadErrorReason = "ERROR_CANNOT_RESUME";
                                                break;
                                            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                                                mDownloadErrorReason = "ERROR_DEVICE_NOT_FOUND";
                                                break;
                                            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                                                mDownloadErrorReason = "ERROR_FILE_ALREADY_EXISTS";
                                                break;
                                            case DownloadManager.ERROR_FILE_ERROR:
                                                mDownloadErrorReason = "ERROR_FILE_ERROR";
                                                break;
                                            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                                                mDownloadErrorReason = "ERROR_HTTP_DATA_ERROR";
                                                break;
                                            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                                                mDownloadErrorReason = "ERROR_INSUFFICIENT_SPACE";
                                                break;
                                            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                                                mDownloadErrorReason = "ERROR_TOO_MANY_REDIRECTS";
                                                break;
                                            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                                                mDownloadErrorReason = "ERROR_UNHANDLED_HTTP_CODE";
                                                break;
                                            default:
                                                mDownloadErrorReason = "ERROR_UNKNOWN";
                                                break;
                                        }
                                    }
                                }
                                c.close();
                            }

                            // Unregister receiver
                            context.unregisterReceiver(this);

                            // Release semaphore in any case..
                            Log.d(Updater.class.getName(), "Release semaphore..");
                            mUpdateSemaphore.release();
                        }
                    }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

                    Log.d(Updater.class.getName(), "Aquire semaphore");
                    mUpdateSemaphore = new Semaphore(1);
                    mUpdateSemaphore.acquire();

                    // Here the download is performed
                    Log.d(Updater.class.getName(), "Start download");
                    mDownloadManager = (DownloadManager)context.getSystemService(context.DOWNLOAD_SERVICE);
                    mQueueValue = mDownloadManager.enqueue(localRequest);

                    Log.d(Updater.class.getName(), "Aquire semaphore again");
                    int lastPercentage = 0;
                    while(!mUpdateSemaphore.tryAcquire())
                    {
                        DownloadManager.Query q = new DownloadManager.Query();
                        q.setFilterById(mQueueValue);
                        Cursor cursor = mDownloadManager.query(q);
                        int percentage = 0;
                        if(cursor.moveToFirst())
                        {
                            int bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            int bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            percentage = (int)Math.round((((double)bytes_downloaded / (double)bytes_total) * 100.0) * 8.0/10.0);
                            if(percentage < 0) percentage = 0;
                            if(percentage > 100) percentage = 100;
                        }
                        cursor.close();

                        if(percentage > lastPercentage)
                        {
                            lastPercentage = percentage;
                            fireOnUpdateProgressListener(false, 10 + percentage, "Download in progress..");
                        }

                        Thread.sleep(500);
                    }
                    mUpdateSemaphore.release();
                    mUpdateSemaphore = null;

                    Log.d(Updater.class.getName(), "Download finished");
                    if(!mDownloadSuccessful)
                    {
                        String reason = "";
                        if(mDownloadErrorReason != null)
                        {
                            reason = " Reason: " + mDownloadErrorReason;
                        }
                        throw new Exception("Download failed.." + reason);
                    }

                    context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://" + downloadFile.getAbsolutePath())));
                    fireOnUpdateProgressListener(false, 80, "Download finished, start installation..");

                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(Uri.parse("file://" + downloadFile.getAbsolutePath()), "application/vnd.android.package-archive");
                    installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(installIntent);

                    fireOnUpdateProgressListener(false, 100, "Successfully initiated update..");
                }
                catch(Exception e)
                {
                    Log.d(Updater.class.getName(), "UpdateError: " + e.getMessage());
                    fireOnUpdateProgressListener(true, 100, e.getMessage());
                }
                finally
                {
                    mIsBusy = false;
                }
            }
        });
        updateThread.start();
    }


    /** Check for update */
    public void checkForUpdate()
    {
        checkForUpdate(false);
    }

    /** Check github for update */
    public void checkForUpdate(Boolean synchron)
    {
        Thread checkForUpdateThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if(mIsBusy)
                    {
                        throw new JSONException("Updater is already working..");
                    }
                    mIsBusy = true;

                    Document doc = Jsoup.connect(UPDATEURL).get();
                    Elements files = doc.select("#list tbody tr");
                    String latestApk = files.get(2).select("td").first().text();

                    mApkName = latestApk;

                    LATEST_VERSION = getVersion(mApkName);

                    mApkURL = UPDATEURL + latestApk;

                    fireOnCheckForUpdateFinished("Update finished.");
                    Log.d(Updater.class.getName(), "Update finished successful, found version: " + LATEST_VERSION);
                }
                catch (IOException e)
                {
                    Log.d(Updater.class.getName(), "IOError: " + e.getMessage());
                    fireOnCheckForUpdateFinished("Update-Check-Error with connection: " + e.getMessage());
                }
                catch (JSONException e)
                {
                    Log.d(Updater.class.getName(), "ParseError: " + e.getMessage());
                    fireOnCheckForUpdateFinished("Update-Check-Error with parsing: " + e.getMessage());
                }
                catch (Exception e)
                {
                    Log.d(Updater.class.getName(), "GeneralError: " + e.getMessage());
                    fireOnCheckForUpdateFinished("Update-Check-Error with parsing: " + e.getMessage());
                }
                finally
                {
                    mIsBusy = false;
                }
            }
        });
        checkForUpdateThread.start();
        if(synchron)
        {
            try
            {
                checkForUpdateThread.join();
            }
            catch (InterruptedException ignore) {}
        }
    }

    /**
     * Fire update progress
     * @param percent Percentage
     * @param message Message
     */
    private void fireOnUpdateProgressListener(final Boolean isError, final Integer percent, final String message)
    {
        Thread fireThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                if(mOnUpdateProgressListener != null)
                {
                    mOnUpdateProgressListener.onUpdateProgress(isError, percent, message);
                }
            }
        });
        fireThread.start();
    }

    /**
     * Fire check for update finished message
     * @param message Message to fire
     */
    private void fireOnCheckForUpdateFinished(final String message)
    {
        Thread fireThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                if(mOnCheckForUpdateFinishedListener != null)
                {
                    mOnCheckForUpdateFinishedListener.onCheckForUpdateFinished(message);
                }
            }
        });
        fireThread.start();
    }

    /**
     * Interface for progress messages of update check
     */
    public interface OnCheckForUpdateFinishedListener
    {
        public void onCheckForUpdateFinished(String message);
    }

    /**
     * Interface for progress messages of performing an update
     */
    public interface OnUpdateProgressListener
    {
        public void onUpdateProgress(Boolean isError, Integer percent, String message);
    }

}