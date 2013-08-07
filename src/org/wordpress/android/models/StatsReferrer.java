
package org.wordpress.android.models;

import org.json.JSONException;
import org.json.JSONObject;

import org.wordpress.android.util.StatUtils;

public class StatsReferrer {

    private String mBlogId;
    private long mDate;
    private String mTitle;
    private int mViews;
    private String mUrl;
    private String mImageUrl;

    public StatsReferrer(String blogId, long date, String title, int views, String url, String imageUrl) {
        this.mBlogId = blogId;
        this.mDate = date;
        this.mTitle = title;
        this.mViews = views;
        this.mUrl = url;
        this.mImageUrl = imageUrl;
    }

    public StatsReferrer(String blogId, JSONObject result) throws JSONException {
        setBlogId(blogId);
        setDate(StatUtils.toMs(result.getString("date")));
        setTitle(result.getString("title"));
        setViews(result.getInt("views"));
        setUrl(result.getString("url"));
        if (result.has("imageUrl"))
            setImageUrl(result.getString("imageUrl"));
    }

    public String getBlogId() {
        return mBlogId;
    }

    public void setBlogId(String blogId) {
        this.mBlogId = blogId;
    }

    public long getDate() {
        return mDate;
    }

    public void setDate(long date) {
        this.mDate = date;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setTitle(String title) {
        this.mTitle = title;
    }

    public int getViews() {
        return mViews;
    }

    public void setViews(int views) {
        this.mViews = views;
    }

    public String getUrl() {
        return mUrl;
    }

    public void setUrl(String url) {
        this.mUrl = url;
    }

    public String getImageUrl() {
        return mImageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.mImageUrl = imageUrl;
    }
}