package org.wordpress.android.ui.reader.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.text.TextUtils;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONObject;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.models.ReaderPostList;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.models.ReaderTagType;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderEvents;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResult;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResultListener;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.UrlUtils;

import de.greenrobot.event.EventBus;

/**
 * service which updates posts with specific tags or in specific blogs/feeds - relies on
 * EventBus to alert of update status
 */

public class ReaderPostService extends Service {

    private static final String ARG_TAG     = "tag";
    private static final String ARG_ACTION  = "action";
    private static final String ARG_BLOG_ID = "blog_id";
    private static final String ARG_FEED_ID = "feed_id";

    public static enum UpdateAction {REQUEST_NEWER, REQUEST_OLDER}

    /*
     * update posts with the passed tag
     */
    public static void startServiceForTag(Context context, ReaderTag tag, UpdateAction action) {
        Intent intent = new Intent(context, ReaderPostService.class);
        intent.putExtra(ARG_TAG, tag);
        intent.putExtra(ARG_ACTION, action);
        context.startService(intent);
    }

    /*
     * update posts in the passed blog
     */
    public static void startServiceForBlog(Context context, long blogId, UpdateAction action) {
        Intent intent = new Intent(context, ReaderPostService.class);
        intent.putExtra(ARG_BLOG_ID, blogId);
        intent.putExtra(ARG_ACTION, action);
        context.startService(intent);
    }

    /*
     * update posts in the passed feed
     */
    public static void startServiceForFeed(Context context, long feedId, UpdateAction action) {
        Intent intent = new Intent(context, ReaderPostService.class);
        intent.putExtra(ARG_FEED_ID, feedId);
        intent.putExtra(ARG_ACTION, action);
        context.startService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.i(AppLog.T.READER, "reader post service > created");
    }

    @Override
    public void onDestroy() {
        AppLog.i(AppLog.T.READER, "reader post service > destroyed");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        UpdateAction action;
        if (intent.hasExtra(ARG_ACTION)) {
            action = (UpdateAction) intent.getSerializableExtra(ARG_ACTION);
        } else {
            action = UpdateAction.REQUEST_NEWER;
        }

        if (intent.hasExtra(ARG_TAG)) {
            ReaderTag tag = (ReaderTag) intent.getSerializableExtra(ARG_TAG);
            updatePostsWithTag(tag, action);
        } else if (intent.hasExtra(ARG_BLOG_ID)) {
            long blogId = intent.getLongExtra(ARG_BLOG_ID, 0);
            updatePostsInBlog(blogId, action);
        } else if (intent.hasExtra(ARG_FEED_ID)) {
            long feedId = intent.getLongExtra(ARG_FEED_ID, 0);
            updatePostsInFeed(feedId, action);
        }

        return START_NOT_STICKY;
    }

    void updatePostsWithTag(final ReaderTag tag, final UpdateAction action) {
        requestPostsWithTag(
                tag,
                action,
                new UpdateResultListener() {
                    @Override
                    public void onUpdateResult(UpdateResult result) {
                        EventBus.getDefault().post(new ReaderEvents.UpdatePostsEnded(tag, result, action));
                        stopSelf();
                    }
                });
        EventBus.getDefault().post(new ReaderEvents.UpdatePostsStarted(action));
    }

    void updatePostsInBlog(long blogId, final UpdateAction action) {
        UpdateResultListener listener = new UpdateResultListener() {
            @Override
            public void onUpdateResult(UpdateResult result) {
                EventBus.getDefault().post(new ReaderEvents.UpdatePostsEnded(result, action));
                stopSelf();
            }
        };
        requestPostsForBlog(blogId, action, listener);
    }

    void updatePostsInFeed(long feedId, final UpdateAction action) {
        UpdateResultListener listener = new UpdateResultListener() {
            @Override
            public void onUpdateResult(UpdateResult result) {
                EventBus.getDefault().post(new ReaderEvents.UpdatePostsEnded(result, action));
                stopSelf();
            }
        };
        requestPostsForFeed(feedId, action, listener);
    }

    private static void requestPostsWithTag(final ReaderTag tag,
                                            final UpdateAction updateAction,
                                            final UpdateResultListener resultListener) {
        String endpoint = getEndpointForTag(tag);
        if (TextUtils.isEmpty(endpoint)) {
            resultListener.onUpdateResult(UpdateResult.FAILED);
            return;
        }

        StringBuilder sb = new StringBuilder(endpoint);

        // append #posts to retrieve
        sb.append("?number=").append(ReaderConstants.READER_MAX_POSTS_TO_REQUEST);

        // return newest posts first (this is the default, but make it explicit since it's important)
        sb.append("&order=DESC");

        // if older posts are being requested, add the &before param based on the oldest existing post
        if (updateAction == UpdateAction.REQUEST_OLDER) {
            String dateOldest = ReaderPostTable.getOldestPubDateWithTag(tag);
            if (!TextUtils.isEmpty(dateOldest)) {
                sb.append("&before=").append(UrlUtils.urlEncode(dateOldest));
            }
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                // remember when this tag was updated if newer posts were requested
                if (updateAction == UpdateAction.REQUEST_NEWER) {
                    ReaderTagTable.setTagLastUpdated(tag);
                }
                handleUpdatePostsResponse(tag, jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
        };

        WordPress.getRestClientUtilsV1_1().get(sb.toString(), null, null, listener, errorListener);
    }


    private static void requestPostsForBlog(final long blogId,
                                            final UpdateAction updateAction,
                                            final UpdateResultListener resultListener) {
        String path = "/sites/" + blogId + "/posts/?meta=site,likes";

        // append the date of the oldest cached post in this blog when requesting older posts
        if (updateAction == UpdateAction.REQUEST_OLDER) {
            String dateOldest = ReaderPostTable.getOldestPubDateInBlog(blogId);
            if (!TextUtils.isEmpty(dateOldest)) {
                path += "&before=" + UrlUtils.urlEncode(dateOldest);
            }
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdatePostsResponse(null, jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
        };
        AppLog.d(AppLog.T.READER, "updating posts in blog " + blogId);
        WordPress.getRestClientUtilsV1_1().get(path, null, null, listener, errorListener);
    }

    private static void requestPostsForFeed(final long feedId,
                                            final UpdateAction updateAction,
                                            final UpdateResultListener resultListener) {
        String path = "/read/feed/" + feedId + "/posts/?meta=site,likes";
        if (updateAction == UpdateAction.REQUEST_OLDER) {
            String dateOldest = ReaderPostTable.getOldestPubDateInFeed(feedId);
            if (!TextUtils.isEmpty(dateOldest)) {
                path += "&before=" + UrlUtils.urlEncode(dateOldest);
            }
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdatePostsResponse(null, jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
        };

        AppLog.d(AppLog.T.READER, "updating posts in feed " + feedId);
        WordPress.getRestClientUtilsV1_1().get(path, null, null, listener, errorListener);
    }

    /*
     * called after requesting posts with a specific tag or in a specific blog
     */
    private static void handleUpdatePostsResponse(final ReaderTag tag,
                                                  final JSONObject jsonObject,
                                                  final UpdateResultListener resultListener) {
        if (jsonObject == null) {
            resultListener.onUpdateResult(UpdateResult.FAILED);
            return;
        }

        new Thread() {
            @Override
            public void run() {
                ReaderPostList serverPosts = ReaderPostList.fromJson(jsonObject);
                UpdateResult updateResult = ReaderPostTable.comparePosts(serverPosts);
                if (updateResult.isNewOrChanged()) {
                    ReaderPostTable.addOrUpdatePosts(tag, serverPosts);
                }
                AppLog.d(AppLog.T.READER, "requested posts response = " + updateResult.toString());
                resultListener.onUpdateResult(updateResult);
            }
        }.start();
    }

    /*
     * returns the endpoint to use when requesting posts with the passed tag
     */
    private static String getEndpointForTag(ReaderTag tag) {
        if (tag == null) {
            return null;
        }

        // if passed tag has an assigned endpoint, return it and be done
        if (!TextUtils.isEmpty(tag.getEndpoint())) {
            return getRelativeEndpoint(tag.getEndpoint());
        }

        // check the db for the endpoint
        String endpoint = ReaderTagTable.getEndpointForTag(tag);
        if (!TextUtils.isEmpty(endpoint)) {
            return getRelativeEndpoint(endpoint);
        }

        // never hand craft the endpoint for default tags, since these MUST be updated
        // using their stored endpoints
        if (tag.tagType == ReaderTagType.DEFAULT) {
            return null;
        }

        return String.format("/read/tags/%s/posts", ReaderUtils.sanitizeWithDashes(tag.getTagName()));
    }

    /*
     * returns the passed endpoint without the unnecessary path - this is
     * needed because as of 20-Feb-2015 the /read/menu/ call returns the
     * full path but we don't want to use the full path since it may change
     * between API versions (as it did when we moved from v1 to v1.1)
     *
     * ex: https://public-api.wordpress.com/rest/v1/read/tags/fitness/posts
     *     becomes just                            /read/tags/fitness/posts
     */
    private static String getRelativeEndpoint(final String endpoint) {
        if (endpoint != null && endpoint.startsWith("http")) {
            int pos = endpoint.indexOf("/read/");
            if (pos > -1) {
                return endpoint.substring(pos, endpoint.length());
            }
            pos = endpoint.indexOf("/v1/");
            if (pos > -1) {
                return endpoint.substring(pos + 3, endpoint.length());
            }
        }
        return StringUtils.notNullStr(endpoint);
    }

}