package net.jejer.hipda.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.RequestManager;

import net.jejer.hipda.R;
import net.jejer.hipda.bean.HiSettingsHelper;
import net.jejer.hipda.bean.ThreadBean;
import net.jejer.hipda.glide.GlideHelper;
import net.jejer.hipda.utils.ColorHelper;
import net.jejer.hipda.utils.Utils;

/**
 * Created by GreenSkinMonster on 2016-04-21.
 */
public class ThreadItemLayout extends LinearLayout {

    private TextView mTvTitle;
    private TextView mTvReplycounter;
    private TextView mTvCreateTime;
    private TextView mTvImageIndicator;

    private RequestManager mGlide;

    public ThreadItemLayout(Context context, RequestManager glide) {
        super(context, null, 0);
        inflate(context, R.layout.item_thread_list, this);

        mTvTitle = (TextView) findViewById(R.id.tv_title);
        mTvReplycounter = (TextView) findViewById(R.id.tv_replycounter);
        mTvCreateTime = (TextView) findViewById(R.id.tv_create_time);
        mTvImageIndicator = (TextView) findViewById(R.id.tv_image_indicator);
        mGlide = glide;
    }

    public void setData(final ThreadBean thread) {
        mTvTitle.setTextSize(HiSettingsHelper.getInstance().getTitleTextSize());
        mTvTitle.setText(thread.getTitle());

        String titleColor = Utils.nullToText(thread.getTitleColor()).trim();

        if (titleColor.startsWith("#")) {
            try {
                mTvTitle.setTextColor(Color.parseColor(titleColor));
            } catch (Exception ignored) {
                mTvTitle.setTextColor(ColorHelper.getTextColorPrimary(getContext()));
            }
        } else
            mTvTitle.setTextColor(ColorHelper.getTextColorPrimary(getContext()));

        mTvReplycounter.setText(
                Utils.toCountText(thread.getCountCmts())
                        + "/"
                        + Utils.toCountText(thread.getCountViews()));

        mTvCreateTime.setText(Utils.shortyTime(thread.getTimeCreate()));

        if (thread.getHavePic()) {
            mTvImageIndicator.setVisibility(View.VISIBLE);
        } else {
            mTvImageIndicator.setVisibility(View.GONE);
        }
    }

}
