/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.browser;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;
import android.webkit.WebStorage;

import java.io.File;
import java.util.Set;


/**
 * Package level class for managing the disk size consumed by the WebDatabase
 * and ApplicationCaches APIs (henceforth called Web storage).
 *
 * Currently, the situation on the WebKit side is as follows:
 *  - WebDatabase enforces a quota for each origin.
 *  - Session/LocalStorage do not enforce any disk limits.
 *  - ApplicationCaches enforces a maximum size for all origins.
 *
 * The WebStorageSizeManager maintains a global limit for the disk space
 * consumed by the WebDatabase and ApplicationCaches. As soon as WebKit will
 * have a limit for Session/LocalStorage, this class will manage the space used
 * by those APIs as well.
 *
 * The global limit is computed as a function of the size of the partition where
 * these APIs store their data (they must store it on the same partition for
 * this to work) and the size of the available space on that partition.
 * The global limit is not subject to user configuration but we do provide
 * a debug-only setting.
 * TODO(andreip): implement the debug setting.
 *
 * The size of the disk space used for Web storage is initially divided between
 * WebDatabase and ApplicationCaches as follows:
 *
 * 75% for WebDatabase
 * 25% for ApplicationCaches
 *
 * When an origin's database usage reaches its current quota, WebKit invokes
 * the following callback function:
 * - exceededDatabaseQuota(Frame* frame, const String& database_name);
 * Note that the default quota for a new origin is 0, so we will receive the
 * 'exceededDatabaseQuota' callback before a new origin gets the chance to
 * create its first database.
 *
 * When the total ApplicationCaches usage reaches its current quota, WebKit
 * invokes the following callback function:
 * - void reachedMaxAppCacheSize(int64_t spaceNeeded);
 *
 * The WebStorageSizeManager's main job is to respond to the above two callbacks
 * by inspecting the amount of unused Web storage quota (i.e. global limit -
 * sum of all other origins' quota) and deciding if a quota increase for the
 * out-of-space origin is allowed or not.
 *
 * The default quota for an origin is min(ORIGIN_DEFAULT_QUOTA, unused_quota).
 * Quota increases are done in steps, where the increase step is
 * min(QUOTA_INCREASE_STEP, unused_quota).
 *
 * This approach has the drawback that space may remain unused if there
 * are many websites that store a lot less content than ORIGIN_DEFAULT_QUOTA.
 * We deal with this by picking a value for ORIGIN_DEFAULT_QUOTA that is smaller
 * than what the HTML 5 spec recommends. At the same time, picking a very small
 * value for ORIGIN_DEFAULT_QUOTA may create performance problems since it's
 * more likely for origins to have to rollback and restart transactions as a
 * result of reaching the quota more often.
 *
 * When all the Web storage space is used, the WebStorageSizeManager creates
 * a system notification that will guide the user to the WebSettings UI. There,
 * the user can free some of the Web storage space by deleting all the data used
 * by an origin.
 * TODO(andreip): implement the notification.
 */
class WebStorageSizeManager {
    // Logging flags.
    private final static boolean LOGV_ENABLED = com.android.browser.Browser.LOGV_ENABLED;
    private final static boolean LOGD_ENABLED = com.android.browser.Browser.LOGD_ENABLED;
    private final static String LOGTAG = "browser";
    // The default quota value for an origin.
    public final static long ORIGIN_DEFAULT_QUOTA = 3 * 1024 * 1024;  // 3MB
    // The default value for quota increases.
    public final static long QUOTA_INCREASE_STEP = 1 * 1024 * 1024;  // 1MB
    // The application context.
    private final Context mContext;
    // The global Web storage limit.
    private final long mGlobalLimit;
    // The maximum size of the application cache file.
    private long mAppCacheMaxSize;

    /**
     * Interface used by the WebStorageSizeManager to obtain information
     * about the underlying file system. This functionality is separated
     * into its own interface mainly for testing purposes.
     */
    public interface DiskInfo {
        /**
         * @return the size of the free space in the file system.
         */
        public long getFreeSpaceSizeBytes();

        /**
         * @return the total size of the file system.
         */
        public long getTotalSizeBytes();
    };

    private DiskInfo mDiskInfo;
    // For convenience, we provide a DiskInfo implementation that uses StatFs.
    public static class StatFsDiskInfo implements DiskInfo {
        private StatFs mFs;

        public StatFsDiskInfo(String path) {
            mFs = new StatFs(path);
        }

        public long getFreeSpaceSizeBytes() {
            return mFs.getAvailableBlocks() * mFs.getBlockSize();
        }

        public long getTotalSizeBytes() {
            return mFs.getBlockCount() * mFs.getBlockSize();
        }
    };

    /**
     * Interface used by the WebStorageSizeManager to obtain information
     * about the appcache file. This functionality is separated into its own
     * interface mainly for testing purposes.
     */
    public interface AppCacheInfo {
        /**
         * @return the current size of the appcache file.
         */
        public long getAppCacheSizeBytes();
    };

    // For convenience, we provide an AppCacheInfo implementation.
    public static class WebKitAppCacheInfo implements AppCacheInfo {
        // The name of the application cache file. Keep in sync with
        // WebCore/loader/appcache/ApplicationCacheStorage.cpp
        private final static String APPCACHE_FILE = "ApplicationCache.db";
        private String mAppCachePath;

        public WebKitAppCacheInfo(String path) {
            mAppCachePath = path;
        }

        public long getAppCacheSizeBytes() {
            File file = new File(mAppCachePath
                    + File.separator
                    + APPCACHE_FILE);
            return file.length();
        }
    };

    /**
     * Public ctor
     * @param ctx is the application context
     * @param diskInfo is the DiskInfo instance used to query the file system.
     * @param appCacheInfo is the AppCacheInfo used to query info about the
     * appcache file.
     */
    public WebStorageSizeManager(Context ctx, DiskInfo diskInfo,
            AppCacheInfo appCacheInfo) {
        mContext = ctx;
        mDiskInfo = diskInfo;
        mGlobalLimit = getGlobalLimit();
        // The initial max size of the app cache is either 25% of the global
        // limit or the current size of the app cache file, whichever is bigger.
        mAppCacheMaxSize = Math.max(mGlobalLimit / 4,
                appCacheInfo.getAppCacheSizeBytes());
    }

    /**
     * Returns the maximum size of the application cache.
     */
    public long getAppCacheMaxSize() {
        return mAppCacheMaxSize;
    }

    /**
     * The origin has exceeded its database quota.
     * @param url the URL that exceeded the quota
     * @param databaseIdentifier the identifier of the database on
     *     which the transaction that caused the quota overflow was run
     * @param currentQuota the current quota for the origin.
     * @param totalUsedQuota is the sum of all origins' quota.
     * @param quotaUpdater The callback to run when a decision to allow or
     *     deny quota has been made. Don't forget to call this!
     */
    public void onExceededDatabaseQuota(String url,
        String databaseIdentifier, long currentQuota, long totalUsedQuota,
        WebStorage.QuotaUpdater quotaUpdater) {
        if(LOGV_ENABLED) {
            Log.v(LOGTAG,
                  "Received onExceededDatabaseQuota for "
                  + url
                  + ":"
                  + databaseIdentifier
                  + "(current quota: "
                  + currentQuota
                  + ", total used quota: "
                  + totalUsedQuota
                  + ")");
        }
        long totalUnusedQuota = mGlobalLimit - totalUsedQuota - mAppCacheMaxSize;

        if (totalUnusedQuota <= 0) {
            // There definitely isn't any more space. Fire notifications
            // and exit.
            scheduleOutOfSpaceNotification();
            quotaUpdater.updateQuota(currentQuota);
            if(LOGV_ENABLED) {
                Log.v(LOGTAG, "onExceededDatabaseQuota: out of space.");
            }
            return;
        }
        // We have enough space inside mGlobalLimit.
        long newOriginQuota = currentQuota;
        if (newOriginQuota == 0) {
            // This is a new origin. It wants an initial quota.
            newOriginQuota =
                Math.min(ORIGIN_DEFAULT_QUOTA, totalUnusedQuota);
        } else {
            // This is an origin we have seen before. It wants a quota
            // increase.
            newOriginQuota +=
                Math.min(QUOTA_INCREASE_STEP, totalUnusedQuota);
        }
        quotaUpdater.updateQuota(newOriginQuota);

        if(LOGV_ENABLED) {
            Log.v(LOGTAG, "onExceededDatabaseQuota set new quota to "
                    + newOriginQuota);
        }
    }

    /**
     * The Application Cache has exceeded its max size.
     * @param spaceNeeded is the amount of disk space that would be needed
     * in order for the last appcache operation to succeed.
     * @param totalUsedQuota is the sum of all origins' quota.
     * @param quotaUpdater A callback to inform the WebCore thread that a new
     * app cache size is available. This callback must always be executed at
     * some point to ensure that the sleeping WebCore thread is woken up.
     */
    public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
            WebStorage.QuotaUpdater quotaUpdater) {
        if(LOGV_ENABLED) {
            Log.v(LOGTAG, "Received onReachedMaxAppCacheSize with spaceNeeded "
                  + spaceNeeded + " bytes.");
        }

        long totalUnusedQuota = mGlobalLimit - totalUsedQuota - mAppCacheMaxSize;

        if (totalUnusedQuota < spaceNeeded) {
            // There definitely isn't any more space. Fire notifications
            // and exit.
            scheduleOutOfSpaceNotification();
            quotaUpdater.updateQuota(0);
            if(LOGV_ENABLED) {
                Log.v(LOGTAG, "onReachedMaxAppCacheSize: out of space.");
            }
            return;
        }
        // There is enough space to accommodate spaceNeeded bytes.
        mAppCacheMaxSize += spaceNeeded;
        quotaUpdater.updateQuota(mAppCacheMaxSize);

        if(LOGV_ENABLED) {
            Log.v(LOGTAG, "onReachedMaxAppCacheSize set new max size to "
                    + mAppCacheMaxSize);
        }
    }

    // Computes the global limit as a function of the size of the data
    // partition and the amount of free space on that partition.
    private long getGlobalLimit() {
        long freeSpace = mDiskInfo.getFreeSpaceSizeBytes();
        long fileSystemSize = mDiskInfo.getTotalSizeBytes();
        return calculateGlobalLimit(fileSystemSize, freeSpace);
    }

    /*package*/ static long calculateGlobalLimit(long fileSystemSizeBytes,
            long freeSpaceBytes) {
        if (fileSystemSizeBytes <= 0
                || freeSpaceBytes <= 0
                || freeSpaceBytes > fileSystemSizeBytes) {
            return 0;
        }

        long fileSystemSizeRatio =
            2 << ((int) Math.floor(Math.log10(
                    fileSystemSizeBytes / (1024 * 1024))));
        long maxSizeBytes = (long) Math.min(Math.floor(
                fileSystemSizeBytes / fileSystemSizeRatio),
                Math.floor(freeSpaceBytes / 2));
        // Round maxSizeBytes up to a multiple of 1024KB (but only if
        // maxSizeBytes > 1MB).
        long maxSizeStepBytes = 1024 * 1024;
        if (maxSizeBytes < maxSizeStepBytes) {
            return 0;
        }
        long roundingExtra = maxSizeBytes % maxSizeStepBytes == 0 ? 0 : 1;
        return (maxSizeStepBytes
                * ((maxSizeBytes / maxSizeStepBytes) + roundingExtra));
    }

    // Schedules a system notification that takes the user to the WebSettings
    // activity when clicked.
    private void scheduleOutOfSpaceNotification() {
        // TODO(andreip): implement.
    }
}