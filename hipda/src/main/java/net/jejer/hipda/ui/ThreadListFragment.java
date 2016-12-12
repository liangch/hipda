package net.jejer.hipda.ui;


import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomSheetBehavior;
import android.support.design.widget.BottomSheetDialog;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.iconics.view.IconicsImageView;

import net.jejer.hipda.R;
import net.jejer.hipda.async.LoginHelper;
import net.jejer.hipda.async.NetworkReadyEvent;
import net.jejer.hipda.async.PostHelper;
import net.jejer.hipda.bean.HiSettingsHelper;
import net.jejer.hipda.bean.NotificationBean;
import net.jejer.hipda.bean.PostBean;
import net.jejer.hipda.bean.ThreadBean;
import net.jejer.hipda.bean.ThreadListBean;
import net.jejer.hipda.db.HistoryDao;
import net.jejer.hipda.job.EventCallback;
import net.jejer.hipda.job.JobMgr;
import net.jejer.hipda.job.PostEvent;
import net.jejer.hipda.job.ThreadListEvent;
import net.jejer.hipda.job.ThreadListJob;
import net.jejer.hipda.ui.adapter.RecyclerItemClickListener;
import net.jejer.hipda.ui.adapter.ThreadListAdapter;
import net.jejer.hipda.ui.widget.BottomDialog;
import net.jejer.hipda.ui.widget.ContentLoadingView;
import net.jejer.hipda.ui.widget.SimpleDivider;
import net.jejer.hipda.ui.widget.XFooterView;
import net.jejer.hipda.ui.widget.XRecyclerView;
import net.jejer.hipda.utils.ColorHelper;
import net.jejer.hipda.utils.Constants;
import net.jejer.hipda.utils.HiUtils;
import net.jejer.hipda.utils.NotificationMgr;
import net.jejer.hipda.utils.UIUtils;
import net.jejer.hipda.utils.Utils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class ThreadListFragment extends BaseFragment
        implements SwipeRefreshLayout.OnRefreshListener {

    private final static int MIN_TREADS_IN_PAGE = 10;
    public static final String ARG_FID_KEY = "fid";

    private Context mCtx;
    private int mForumId = 0;
    private int mPage = 1;
    private ThreadListAdapter mThreadListAdapter;
    private List<ThreadBean> mThreadBeans = new ArrayList<>();
    private XRecyclerView mRecyclerView;
    private boolean mInloading = false;
    private HiProgressDialog postProgressDialog;
    private SwipeRefreshLayout swipeLayout;
    private ContentLoadingView mLoadingView;
    private int mFirstVisibleItem = 0;
    private boolean mDataReceived = false;

    private MenuItem mForumTypeMenuItem;
    private final int[] mFidHolder = new int[1];

    private ThreadListEventCallback mEventCallback = new ThreadListEventCallback();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCtx = getActivity();

        if (getArguments() != null && getArguments().containsKey(ARG_FID_KEY)) {
            mForumId = getArguments().getInt(ARG_FID_KEY);
        }
        int forumIdx = HiUtils.getForumIndexByFid(mForumId);
        if (forumIdx == -1) {
            mForumId = HiUtils.FID_DISCOVERY;
        }
        mFidHolder[0] = mForumId;

        HiSettingsHelper.getInstance().setLastForumId(mForumId);

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_thread_list, parent, false);
        mRecyclerView = (XRecyclerView) view.findViewById(R.id.rv_threads);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mCtx));
        mRecyclerView.addItemDecoration(new SimpleDivider(
                mCtx.obtainStyledAttributes(new int[]{R.attr.list_item_divider}).getDrawable(0)));

        RecyclerItemClickListener itemClickListener = new RecyclerItemClickListener(mCtx, new OnItemClickListener());

        mThreadListAdapter = new ThreadListAdapter(Glide.with(this), itemClickListener);
        mThreadListAdapter.setDatas(mThreadBeans);

        mRecyclerView.setAdapter(mThreadListAdapter);
        mRecyclerView.addOnScrollListener(new OnScrollListener());

        swipeLayout = (SwipeRefreshLayout) view.findViewById(R.id.swipe_container);
        swipeLayout.setOnRefreshListener(this);
        swipeLayout.setColorSchemeColors(ColorHelper.getSwipeColor(getActivity()));
        swipeLayout.setProgressBackgroundColorSchemeColor(ColorHelper.getSwipeBackgroundColor(getActivity()));

        mLoadingView = (ContentLoadingView) view.findViewById(R.id.content_loading);
        mLoadingView.setErrorStateListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!mInloading) {
                    mInloading = true;
                    mLoadingView.setState(ContentLoadingView.LOAD_NOW);
                    refresh();
                }
            }
        });

        mRecyclerView.scrollToPosition(mFirstVisibleItem);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (savedInstanceState != null) {
            mCtx = getActivity();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().register(this);
        if (!mInloading) {
            if (mThreadBeans.size() == 0) {
                mLoadingView.setState(ContentLoadingView.LOAD_NOW);
                mInloading = true;
                ThreadListJob job = new ThreadListJob(getActivity(), mSessionId, mForumId, mPage);
                JobMgr.addJob(job);
            } else {
                swipeLayout.setRefreshing(false);
                mLoadingView.setState(ContentLoadingView.CONTENT);
                hideFooter();
            }
        }
    }

    @Override
    public void onPause() {
        EventBus.getDefault().unregister(this);
        super.onPause();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.menu_thread_list, menu);
        if (mForumId == HiUtils.FID_BS) {
            mForumTypeMenuItem = menu.findItem(R.id.action_filter_by_type);
            mForumTypeMenuItem.setVisible(true);
            String typeId = HiSettingsHelper.getInstance().getBSTypeId();
            int typeIdIndex = HiUtils.getBSTypeIndexByFid(typeId);
            if (typeIdIndex == -1) typeIdIndex = 0;
            if (mCtx != null)
                mForumTypeMenuItem.setIcon(new IconicsDrawable(mCtx, HiUtils.BS_TYPE_ICONS[typeIdIndex]).color(Color.WHITE).actionBar());
        }

        int forumIdx = HiUtils.getForumIndexByFid(mForumId);

        setActionBarTitle(HiUtils.FORUM_NAMES[forumIdx]);
        setActionBarDisplayHomeAsUpEnabled(false);
        syncActionBarState();

        setDrawerSelection(mForumId);

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (LoginHelper.isLoggedIn()) {
            showNotification();
        } else if (!HiSettingsHelper.getInstance().isLoginInfoValid()) {
            showLoginDialog();
        }
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // Implemented in activity
                return false;
//            case R.id.action_refresh_list:
//                refresh();
//                return true;
            case R.id.action_thread_list_settings:
                showThreadListSettingsDialog();
                return true;
            case R.id.action_new_thread:
                newThread();
                return true;
            case R.id.action_filter_by_type:
                showForumTypesDialog();
                return true;
            case R.id.action_open_by_url:
                showOpenUrlDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    void setupFab() {
        if (mMainFab != null) {
            mMainFab.setEnabled(true);
            mMainFab.setImageResource(R.drawable.ic_refresh_white_24dp);
            mMainFab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mLoadingView.setState(ContentLoadingView.LOAD_NOW);
                    if (swipeLayout.isShown())
                        swipeLayout.setRefreshing(false);
                    refresh();
                }
            });
        }
        if (mThreadBeans.size() > 0)
            mMainFab.show();

        if (mNotificationFab != null) {
            mNotificationFab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    NotificationBean bean = NotificationMgr.getCurrentNotification();
                    if (bean.getSmsCount() == 1
                            && bean.getThreadCount() == 0
                            && HiUtils.isValidId(bean.getUid())
                            && !TextUtils.isEmpty(bean.getUsername())) {
                        FragmentUtils.showSmsDetail(getFragmentManager(), true, bean.getUid(), bean.getUsername());
                        NotificationMgr.getCurrentNotification().clearSmsCount();
                        showNotification();
                    } else if (bean.getSmsCount() > 0) {
                        FragmentUtils.showSmsList(getFragmentManager(), true);
                    } else if (bean.getThreadCount() > 0) {
                        FragmentUtils.showThreadNotify(getFragmentManager(), true);
                        NotificationMgr.getCurrentNotification().setThreadCount(0);
                        showNotification();
                    } else {
                        Toast.makeText(mCtx, "没有未处理的通知", Toast.LENGTH_SHORT).show();
                        mNotificationFab.hide();
                    }
                }
            });
        }
    }

    private void newThread() {
        Bundle arguments = new Bundle();
        arguments.putInt(PostFragment.ARG_MODE_KEY, PostHelper.MODE_NEW_THREAD);
        arguments.putString(PostFragment.ARG_FID_KEY, mForumId + "");

        PostFragment fragment = new PostFragment();
        fragment.setParentSessionId(mSessionId);
        fragment.setArguments(arguments);
        setHasOptionsMenu(false);

        FragmentUtils.showFragment(getFragmentManager(), fragment);
    }

    public void resetActionBarTitle() {
        int forumIdx = HiUtils.getForumIndexByFid(mForumId);
        setActionBarTitle(HiUtils.FORUM_NAMES[forumIdx]);
        setActionBarDisplayHomeAsUpEnabled(false);
        syncActionBarState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    private void refresh() {
        mPage = 1;
        mRecyclerView.scrollToTop();
        hideFooter();
        mInloading = true;
        if (HiSettingsHelper.getInstance().isFabAutoHide())
            mMainFab.hide();
        ThreadListJob job = new ThreadListJob(getActivity(), mSessionId, mForumId, mPage);
        JobMgr.addJob(job);
    }

    @Override
    public void onRefresh() {
        refresh();
        if (mThreadBeans.size() > 0)
            mLoadingView.setState(ContentLoadingView.CONTENT);
    }

    private class OnScrollListener extends RecyclerView.OnScrollListener {
        int visibleItemCount, totalItemCount;

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (dy > 0) {
                LinearLayoutManager mLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                visibleItemCount = mLayoutManager.getChildCount();
                totalItemCount = mLayoutManager.getItemCount();
                mFirstVisibleItem = mLayoutManager.findFirstVisibleItemPosition();

                if ((visibleItemCount + mFirstVisibleItem) >= totalItemCount - 5) {
                    if (!mInloading) {
                        mPage++;
                        mInloading = true;
                        mRecyclerView.setFooterState(XFooterView.STATE_LOADING);
                        ThreadListJob job = new ThreadListJob(getActivity(), mSessionId, mForumId, mPage);
                        JobMgr.addJob(job);
                    }
                }
            }
        }
    }

    private class OnItemClickListener implements RecyclerItemClickListener.OnItemClickListener {

        @Override
        public void onItemClick(View view, int position) {
            ThreadBean thread = mThreadListAdapter.getItem(position);
            if (thread != null) {
                String tid = thread.getTid();
                String title = thread.getTitle();
                setHasOptionsMenu(false);
                FragmentUtils.showThread(getFragmentManager(), false, tid, title, -1, -1, null, thread.getMaxPage());
                HistoryDao.saveHistoryInBackground(tid, mFidHolder[0] + "",
                        title, thread.getAuthorId(), thread.getAuthor(), thread.getTimeCreate());
            }
        }

        @Override
        public void onLongItemClick(View view, int position) {
            ThreadBean thread = mThreadListAdapter.getItem(position);
            if (thread != null) {
                String tid = thread.getTid();
                String title = thread.getTitle();
                int page = 1;
                int maxPostsInPage = HiSettingsHelper.getInstance().getMaxPostsInPage();
                if (maxPostsInPage > 0 && TextUtils.isDigitsOnly(thread.getCountCmts())) {
                    page = (int) Math.ceil((Integer.parseInt(thread.getCountCmts()) + 1) * 1.0f / maxPostsInPage);
                }
                setHasOptionsMenu(false);
                FragmentUtils.showThread(getFragmentManager(), false, tid, title, page, ThreadDetailFragment.LAST_FLOOR, null, thread.getMaxPage());
                HistoryDao.saveHistoryInBackground(tid, "", title, thread.getAuthorId(), thread.getAuthor(), thread.getTimeCreate());
            }
        }

        @Override
        public void onDoubleTap(View view, int position) {
        }
    }

    private void hideFooter() {
        mRecyclerView.setFooterState(XFooterView.STATE_HIDDEN);
    }

    private void showThreadListSettingsDialog() {
        final LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final View view = inflater.inflate(R.layout.dialog_thread_list_settings, null);

        final Switch sShowStickThreads = (Switch) view.findViewById(R.id.sw_show_stick_threads);
        final Switch sSortByPostTime = (Switch) view.findViewById(R.id.sw_sort_by_post_time);
        final Switch sShowPostType = (Switch) view.findViewById(R.id.sw_show_post_type);

        final BottomSheetDialog dialog = new BottomDialog(getActivity());

        sShowStickThreads.setChecked(HiSettingsHelper.getInstance().isShowStickThreads());
        sShowStickThreads.setOnCheckedChangeListener(new Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton arg0, boolean arg1) {
                HiSettingsHelper.getInstance().setShowStickThreads(arg1);
            }
        });
        sShowPostType.setChecked(HiSettingsHelper.getInstance().isShowPostType());
        sShowPostType.setOnCheckedChangeListener(new Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton arg0, boolean arg1) {
                HiSettingsHelper.getInstance().setShowPostType(arg1);
                mThreadListAdapter.notifyDataSetChanged();
            }
        });
        sSortByPostTime.setChecked(HiSettingsHelper.getInstance().isSortByPostTime(mForumId));
        sSortByPostTime.setOnCheckedChangeListener(new Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton arg0, boolean arg1) {
                HiSettingsHelper.getInstance().setSortByPostTime(mForumId, arg0.isChecked());
            }
        });

        dialog.setContentView(view);
        BottomSheetBehavior mBehavior = BottomSheetBehavior.from((View) view.getParent());
        mBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    private void showForumTypesDialog() {
        final String currentTypeId = HiSettingsHelper.getInstance().getBSTypeId();
        final LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final View viewlayout = inflater.inflate(R.layout.dialog_forum_types, null);

        final ListView listView = (ListView) viewlayout.findViewById(R.id.lv_forum_types);

        listView.setAdapter(new ForumTypesAdapter(getActivity()));

        final AlertDialog.Builder popDialog = new AlertDialog.Builder(getActivity());
        popDialog.setView(viewlayout);
        final AlertDialog dialog = popDialog.create();
        dialog.show();

        listView.setOnItemClickListener(new OnViewItemSingleClickListener() {
            @Override
            public void onItemSingleClick(AdapterView<?> adapterView, View view, int position, long row) {
                dialog.dismiss();
                if (!HiUtils.BS_TYPE_IDS[position].equals(currentTypeId)) {
                    mLoadingView.setState(ContentLoadingView.LOAD_NOW);
                    HiSettingsHelper.getInstance().setBSTypeId(HiUtils.BS_TYPE_IDS[position]);
                    if (mForumTypeMenuItem != null) {
                        mForumTypeMenuItem.setIcon(new IconicsDrawable(getActivity(), HiUtils.BS_TYPE_ICONS[position]).color(Color.WHITE).actionBar());
                    }
                    refresh();
                }
            }
        });
    }

    private void showOpenUrlDialog() {
        final LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final View viewlayout = inflater.inflate(R.layout.dialog_open_by_url, null);

        String urlFromClip = HiUtils.ThreadListUrl;
        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            String text = Utils.nullToText(clipboard.getPrimaryClip().getItemAt(0).getText()).replace("\n", "").trim();
            if (FragmentUtils.parseUrl(text) != null)
                urlFromClip = text;
        }

        final EditText etUrl = (EditText) viewlayout.findViewById(R.id.et_url);
        etUrl.setText(urlFromClip);
        etUrl.selectAll();
        etUrl.requestFocus();

        final AlertDialog.Builder popDialog = new AlertDialog.Builder(getActivity());
        popDialog.setTitle(mCtx.getResources().getString(R.string.action_open_by_url));
        popDialog.setView(viewlayout);
        popDialog.setPositiveButton(getResources().getString(android.R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                FragmentUtils.show(getFragmentManager(), FragmentUtils.parseUrl(Utils.nullToText(etUrl.getText()).replace("\n", "").trim()));
            }
        });

        final AlertDialog dialog = popDialog.create();

        etUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String url = Utils.nullToText(etUrl.getText()).replace("\n", "").trim();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(FragmentUtils.parseUrl(url) != null);
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        dialog.show();

        String url = Utils.nullToText(etUrl.getText()).replace("\n", "").trim();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(FragmentUtils.parseUrl(url) != null);
    }

    public void showNotification() {
        if (mNotificationFab != null) {
            int smsCount = NotificationMgr.getCurrentNotification().getSmsCount();
            int threadCount = NotificationMgr.getCurrentNotification().getThreadCount();
            if (smsCount > 0) {
                mNotificationFab.setImageResource(R.drawable.ic_mail_white_24dp);
                mNotificationFab.setEnabled(true);
                mNotificationFab.show();
            } else if (threadCount > 0) {
                mNotificationFab.setImageResource(R.drawable.ic_notifications_white_24dp);
                mNotificationFab.setEnabled(true);
                mNotificationFab.show();
            } else {
                if (mNotificationFab.isEnabled()) {
                    mNotificationFab.setEnabled(false);
                    mNotificationFab.hide();
                }
            }
            ((MainFrameActivity) getActivity()).updateDrawerBadge();
        }
    }

    public void scrollToTop() {
        mRecyclerView.scrollToTop();
    }

    public void stopScroll() {
        mRecyclerView.dispatchTouchEvent(MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0));
    }

    private class ForumTypesAdapter extends ArrayAdapter {

        public ForumTypesAdapter(Context context) {
            super(context, 0, HiUtils.BS_TYPES);
        }

        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row;
            if (convertView == null) {
                LayoutInflater inflater = getActivity().getLayoutInflater();
                row = inflater.inflate(R.layout.item_forum_type, parent, false);
            } else {
                row = convertView;
            }
            IconicsImageView icon = (IconicsImageView) row.findViewById(R.id.forum_type_icon);
            TextView text = (TextView) row.findViewById(R.id.forum_type_text);

            text.setText(HiUtils.BS_TYPES[position]);
            if (position == HiUtils.getBSTypeIndexByFid(HiSettingsHelper.getInstance().getBSTypeId())) {
                icon.setImageDrawable(new IconicsDrawable(getActivity(), HiUtils.BS_TYPE_ICONS[position]).color(ColorHelper.getColorAccent(getActivity())).sizeDp(20));
                text.setTextColor(ColorHelper.getColorAccent(getActivity()));
            } else {
                icon.setImageDrawable(new IconicsDrawable(getActivity(), HiUtils.BS_TYPE_ICONS[position]).color(ColorHelper.getTextColorPrimary(getActivity())).sizeDp(20));
                text.setTextColor(ColorHelper.getTextColorPrimary(getActivity()));
            }

            return row;
        }
    }

    private class ThreadListEventCallback extends EventCallback<ThreadListEvent> {
        @Override
        public void onSuccess(ThreadListEvent event) {
            mInloading = false;
            swipeLayout.setRefreshing(false);
            mLoadingView.setState(ContentLoadingView.CONTENT);
            hideFooter();

            ThreadListBean threads = event.mData;
            if (TextUtils.isEmpty(HiSettingsHelper.getInstance().getUid())
                    && !TextUtils.isEmpty(threads.getUid())) {
                HiSettingsHelper.getInstance().setUid(threads.getUid());
                if (getActivity() != null)
                    ((MainFrameActivity) getActivity()).updateAccountHeader();
            }

            if (mPage == 1) {
                mThreadBeans.clear();
                mThreadBeans.addAll(threads.getThreads());
                mThreadListAdapter.setDatas(mThreadBeans);
                mRecyclerView.scrollToTop();
            } else {
                for (ThreadBean newthread : threads.getThreads()) {
                    boolean duplicate = false;
                    for (int i = 0; i < mThreadBeans.size(); i++) {
                        ThreadBean oldthread = mThreadBeans.get(i);
                        if (newthread != null && newthread.getTid().equals(oldthread.getTid())) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        mThreadBeans.add(newthread);
                    }
                }
                mThreadListAdapter.setDatas(mThreadBeans);
            }

            if (!mDataReceived) {
                mDataReceived = true;
                mMainFab.show();
            }
            showNotification();

            if (mPage <= 3 && mThreadBeans.size() <= MIN_TREADS_IN_PAGE) {
                mPage++;
                mInloading = true;
                ThreadListJob job = new ThreadListJob(getActivity(), mSessionId, mForumId, mPage);
                JobMgr.addJob(job);
                Toast.makeText(mCtx, "置顶贴较多，请在网页版论坛 个人中心 \n将 论坛个性化设定 - 每页主题 设为 默认", Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onFail(ThreadListEvent event) {
            mInloading = false;
            swipeLayout.setRefreshing(false);
            hideFooter();

            ThreadListBean threads = event.mData;
            if (mPage > 1)
                mPage--;

            boolean fetchNext = false;
            if (mPage <= 3 && threads.isParsed() && mThreadBeans.size() <= MIN_TREADS_IN_PAGE && !HiSettingsHelper.getInstance().isShowStickThreads()) {
                mPage++;
                mInloading = true;
                ThreadListJob job = new ThreadListJob(getActivity(), mSessionId, mForumId, mPage);
                JobMgr.addJob(job);
                fetchNext = true;
                Toast.makeText(mCtx, "置顶贴较多，请在网页版论坛 个人中心 \n将 论坛个性化设定 - 每页主题 设为 默认", Toast.LENGTH_LONG).show();
            }
            if (!fetchNext) {
                if (mThreadBeans.size() > 0) {
                    mLoadingView.setState(ContentLoadingView.CONTENT);
                } else {
                    mLoadingView.setState(ContentLoadingView.ERROR);
                    UIUtils.errorSnack(getView(), event.mMessage, event.mDetail);
                }
            }
        }

        @Override
        public void onFailRelogin(ThreadListEvent event) {
            mInloading = false;
            swipeLayout.setRefreshing(false);
            if (mThreadBeans.size() == 0)
                mLoadingView.setState(ContentLoadingView.ERROR);
            else
                mLoadingView.setState(ContentLoadingView.CONTENT);
            hideFooter();
            showLoginDialog();
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEvent(PostEvent event) {
        if (!mSessionId.equals(event.mSessionId))
            return;

        EventBus.getDefault().removeStickyEvent(event);

        String message = event.mMessage;
        PostBean postResult = event.mPostResult;

        if (event.mStatus == Constants.STATUS_IN_PROGRESS) {
            postProgressDialog = HiProgressDialog.show(mCtx, "正在发表...");
        } else if (event.mStatus == Constants.STATUS_SUCCESS) {
            //pop post fragment on success
            Fragment fg = getFragmentManager().findFragmentById(R.id.main_frame_container);
            if (fg instanceof PostFragment) {
                ((BaseFragment) fg).popFragment();
            }

            if (postProgressDialog != null) {
                postProgressDialog.dismiss(message);
            } else {
                Toast.makeText(mCtx, message, Toast.LENGTH_SHORT).show();
            }

            setHasOptionsMenu(false);
            FragmentUtils.showThread(getFragmentManager(), true, postResult.getTid(), postResult.getSubject(), -1, -1, null, -1);

            refresh();
        } else {
            if (postProgressDialog != null) {
                postProgressDialog.dismissError(message);
            } else {
                Toast.makeText(mCtx, message, Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(NetworkReadyEvent event) {
        if (!mInloading && mThreadBeans.size() == 0)
            refresh();
    }

    @SuppressWarnings("unused")
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEvent(ThreadListEvent event) {
        if (!mSessionId.equals(event.mSessionId))
            return;
        EventBus.getDefault().removeStickyEvent(event);
        mEventCallback.process(event);
    }

}