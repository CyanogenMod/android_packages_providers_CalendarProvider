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
 * limitations under the License
 */

package com.android.providers.calendar;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteTransactionListener;
import android.net.Uri;
import android.os.Binder;
import android.provider.CalendarContract;

import java.util.ArrayList;

/**
 * General purpose {@link ContentProvider} base class that uses SQLiteDatabase for storage.
 */
public abstract class SQLiteContentProvider extends ContentProvider
        implements SQLiteTransactionListener {

    private static final String TAG = "SQLiteContentProvider";

    private SQLiteOpenHelper mOpenHelper;
    private volatile boolean mNotifyChange;
    protected SQLiteDatabase mDb;

    private final ThreadLocal<Boolean> mApplyingBatch = new ThreadLocal<Boolean>();
    private static final int SLEEP_AFTER_YIELD_DELAY = 4000;

    private Boolean mIsCallerSyncAdapter;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        mOpenHelper = getDatabaseHelper(context);
        return true;
    }

    protected abstract SQLiteOpenHelper getDatabaseHelper(Context context);

    /**
     * The equivalent of the {@link #insert} method, but invoked within a transaction.
     */
    protected abstract Uri insertInTransaction(Uri uri, ContentValues values,
            boolean callerIsSyncAdapter);

    /**
     * The equivalent of the {@link #update} method, but invoked within a transaction.
     */
    protected abstract int updateInTransaction(Uri uri, ContentValues values, String selection,
            String[] selectionArgs, boolean callerIsSyncAdapter);

    /**
     * The equivalent of the {@link #delete} method, but invoked within a transaction.
     */
    protected abstract int deleteInTransaction(Uri uri, String selection, String[] selectionArgs,
            boolean callerIsSyncAdapter);

    protected abstract void notifyChange(boolean syncToNetwork);

    protected SQLiteOpenHelper getDatabaseHelper() {
        return mOpenHelper;
    }

    private boolean applyingBatch() {
        return mApplyingBatch.get() != null && mApplyingBatch.get();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Uri result = null;
        boolean applyingBatch = applyingBatch();
        boolean isCallerSyncAdapter = getIsCallerSyncAdapter(uri);
        if (!applyingBatch) {
            mDb = mOpenHelper.getWritableDatabase();
            mDb.beginTransactionWithListener(this);
            final long identity = Binder.clearCallingIdentity();
            try {
                result = insertInTransaction(uri, values, isCallerSyncAdapter);
                if (result != null) {
                    mNotifyChange = true;
                }
                mDb.setTransactionSuccessful();
            } finally {
                Binder.restoreCallingIdentity(identity);
                mDb.endTransaction();
            }

            onEndTransaction(isCallerSyncAdapter);
        } else {
            result = insertInTransaction(uri, values, isCallerSyncAdapter);
            if (result != null) {
                mNotifyChange = true;
            }
        }
        return result;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        int numValues = values.length;
        boolean isCallerSyncAdapter = getIsCallerSyncAdapter(uri);
        mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransactionWithListener(this);
        final long identity = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < numValues; i++) {
                Uri result = insertInTransaction(uri, values[i], isCallerSyncAdapter);
                if (result != null) {
                    mNotifyChange = true;
                }
                mDb.yieldIfContendedSafely();
            }
            mDb.setTransactionSuccessful();
        } finally {
            Binder.restoreCallingIdentity(identity);
            mDb.endTransaction();
        }

        onEndTransaction(isCallerSyncAdapter);
        return numValues;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        boolean applyingBatch = applyingBatch();
        boolean isCallerSyncAdapter = getIsCallerSyncAdapter(uri);
        if (!applyingBatch) {
            mDb = mOpenHelper.getWritableDatabase();
            mDb.beginTransactionWithListener(this);
            final long identity = Binder.clearCallingIdentity();
            try {
                count = updateInTransaction(uri, values, selection, selectionArgs,
                            isCallerSyncAdapter);
                if (count > 0) {
                    mNotifyChange = true;
                }
                mDb.setTransactionSuccessful();
            } finally {
                Binder.restoreCallingIdentity(identity);
                mDb.endTransaction();
            }

            onEndTransaction(isCallerSyncAdapter);
        } else {
            count = updateInTransaction(uri, values, selection, selectionArgs,
                        isCallerSyncAdapter);
            if (count > 0) {
                mNotifyChange = true;
            }
        }

        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        boolean applyingBatch = applyingBatch();
        boolean isCallerSyncAdapter = getIsCallerSyncAdapter(uri);
        if (!applyingBatch) {
            mDb = mOpenHelper.getWritableDatabase();
            mDb.beginTransactionWithListener(this);
            final long identity = Binder.clearCallingIdentity();
            try {
                count = deleteInTransaction(uri, selection, selectionArgs, isCallerSyncAdapter);
                if (count > 0) {
                    mNotifyChange = true;
                }
                mDb.setTransactionSuccessful();
            } finally {
                Binder.restoreCallingIdentity(identity);
                mDb.endTransaction();
            }

            onEndTransaction(isCallerSyncAdapter);
        } else {
            count = deleteInTransaction(uri, selection, selectionArgs, isCallerSyncAdapter);
            if (count > 0) {
                mNotifyChange = true;
            }
        }
        return count;
    }

    protected boolean getIsCallerSyncAdapter(Uri uri) {
        boolean isCurrentSyncAdapter = QueryParameterUtils.readBooleanQueryParameter(uri,
                CalendarContract.CALLER_IS_SYNCADAPTER, false);
        if (mIsCallerSyncAdapter == null || mIsCallerSyncAdapter) {
            mIsCallerSyncAdapter = isCurrentSyncAdapter;
        }
        return isCurrentSyncAdapter;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        final int numOperations = operations.size();
        if (numOperations == 0) {
            return new ContentProviderResult[0];
        }
        mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransactionWithListener(this);
        final boolean isCallerSyncAdapter = getIsCallerSyncAdapter(operations.get(0).getUri());
        final long identity = Binder.clearCallingIdentity();
        try {
            mApplyingBatch.set(true);
            final ContentProviderResult[] results = new ContentProviderResult[numOperations];
            for (int i = 0; i < numOperations; i++) {
                final ContentProviderOperation operation = operations.get(i);
                if (i > 0 && operation.isYieldAllowed()) {
                    mDb.yieldIfContendedSafely(SLEEP_AFTER_YIELD_DELAY);
                }
                results[i] = operation.apply(this, results, i);
            }
            mDb.setTransactionSuccessful();
            return results;
        } finally {
            mApplyingBatch.set(false);
            mDb.endTransaction();
            onEndTransaction(isCallerSyncAdapter);
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onBegin() {
        mIsCallerSyncAdapter = null;
        onBeginTransaction();
    }

    public void onCommit() {
        beforeTransactionCommit();
    }

    public void onRollback() {
        // not used
    }

    protected void onBeginTransaction() {
    }

    protected void beforeTransactionCommit() {
    }

    protected void onEndTransaction(boolean isCallerSyncAdapter) {
        if (mNotifyChange) {
            mNotifyChange = false;
            // We sync to network if the caller was not the sync adapter
            notifyChange(!isCallerSyncAdapter);
        }
    }
}
