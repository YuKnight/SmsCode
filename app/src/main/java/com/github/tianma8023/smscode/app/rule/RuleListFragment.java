package com.github.tianma8023.smscode.app.rule;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.internal.MDButton;
import com.github.tianma8023.smscode.R;
import com.github.tianma8023.smscode.backup.BackupManager;
import com.github.tianma8023.smscode.backup.ExportResult;
import com.github.tianma8023.smscode.backup.ImportResult;
import com.github.tianma8023.smscode.db.DBManager;
import com.github.tianma8023.smscode.entity.SmsCodeRule;
import com.github.tianma8023.smscode.event.Event;
import com.github.tianma8023.smscode.event.XEventBus;
import com.github.tianma8023.smscode.utils.SnackbarHelper;
import com.github.tianma8023.smscode.utils.Utils;
import com.github.tianma8023.smscode.utils.XLog;
import com.github.tianma8023.smscode.widget.DialogAsyncTask;
import com.github.tianma8023.smscode.widget.FabScrollBehavior;
import com.github.tianma8023.smscode.widget.TextWatcherAdapter;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.yanzhenjie.permission.AndPermission;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import butterknife.BindView;
import butterknife.ButterKnife;


/**
 * SMS code codeRule list fragment
 */
public class RuleListFragment extends Fragment {

    private static final int REQUEST_CODE_EXPORT_RULES = 0xfff;
    private static final int REQUEST_CODE_IMPORT_RULES = 0xffe;

    private static final int TYPE_EXPORT = 1;
    private static final int TYPE_IMPORT = 2;
    private static final int TYPE_IMPORT_DIRECT = 3;

    public static final String EXTRA_IMPORT_URI = "extra_import_uri";

    @IntDef({TYPE_EXPORT, TYPE_IMPORT, TYPE_IMPORT_DIRECT})
    @interface BackupType {
    }

    @BindView(R.id.rule_list_recycler_view)
    RecyclerView mRecyclerView;

    private RuleAdapter mRuleAdapter;

    @BindView(R.id.rule_list_fab)
    FloatingActionButton mFabButton;

    @BindView(R.id.empty_view)
    View mEmptyView;

    private int mSelectedPosition = -1;

    private Activity mActivity;

    public static RuleListFragment newInstance(Uri importUri) {
        Bundle args = new Bundle();
        args.putParcelable(EXTRA_IMPORT_URI, importUri);

        RuleListFragment fragment = new RuleListFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public static RuleListFragment newInstance() {
        return newInstance(null);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_rule_list, container, false);
        ButterKnife.bind(this, rootView);
        return rootView;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mActivity = getActivity();

        List<SmsCodeRule> rules = DBManager.get(mActivity).queryAllSmsCodeRules();
        mRuleAdapter = new RuleAdapter(rules);
        mRecyclerView.setAdapter(mRuleAdapter);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(mActivity));
        mRecyclerView.addItemDecoration(new DividerItemDecoration(mActivity, DividerItemDecoration.VERTICAL));

        // swipe to remove
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(mSwipeToRemoveCallback);
        itemTouchHelper.attachToRecyclerView(mRecyclerView);

        mRuleAdapter.setContextMenuListener((menu, v, menuInfo, position) -> {
            mSelectedPosition = position;
            onCreateContextMenu(menu, v, menuInfo);
        });

        mRuleAdapter.setOnItemClickListener((adapter, view, position) -> {
            mSelectedPosition = position;
            XEventBus.post(new Event.StartRuleEditEvent(
                    RuleEditFragment.EDIT_TYPE_UPDATE, mRuleAdapter.getItem(position)));
        });

        mRuleAdapter.registerAdapterDataObserver(mDataObserver);

        // fab settings
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) mFabButton.getLayoutParams();
        params.setBehavior(new FabScrollBehavior());
        mFabButton.setLayoutParams(params);

        mFabButton.setOnClickListener(v -> {
            SmsCodeRule emptyRule = new SmsCodeRule();
            XEventBus.post(new Event.StartRuleEditEvent(
                    RuleEditFragment.EDIT_TYPE_CREATE, emptyRule));
        });

        refreshEmptyView();

        onHandleArguments(getArguments());
    }

    /**
     * Handle arguments
     */
    private void onHandleArguments(Bundle args) {
        if (args == null) {
            return;
        }

        final Uri importUri = args.getParcelable(EXTRA_IMPORT_URI);
        if (importUri != null) {
            args.remove(EXTRA_IMPORT_URI);

            if (ContentResolver.SCHEME_FILE.equals(importUri.getScheme())) {
                // file:// URI need storage permission
                importOrExportRuleList(TYPE_IMPORT_DIRECT, importUri);
            } else {
                // content:// URI don't need storage permission
                showImportDialogConfirm(importUri);
            }
        }
    }

    private void refreshData() {
        List<SmsCodeRule> rules = DBManager.get(mActivity).queryAllSmsCodeRules();
        mRuleAdapter.setRules(rules);
    }

    @Override
    public void onStart() {
        super.onStart();
        XEventBus.register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        XEventBus.unregister(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mRuleAdapter.unregisterAdapterDataObserver(mDataObserver);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_rule_list, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_import_rules:
                importOrExportRuleList(TYPE_IMPORT, null);
                break;
            case R.id.action_export_rules:
                importOrExportRuleList(TYPE_EXPORT, null);
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MenuInflater inflater = mActivity.getMenuInflater();
        inflater.inflate(R.menu.context_rule_list, menu);
        menu.setHeaderTitle(R.string.actions);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        SmsCodeRule smsCodeRule = mRuleAdapter.getItem(mSelectedPosition);
        switch (item.getItemId()) {
            case R.id.action_edit_rule:
                XEventBus.post(new Event.StartRuleEditEvent(
                        RuleEditFragment.EDIT_TYPE_UPDATE, smsCodeRule));
                break;
            case R.id.action_remove_rule:
                removeItemAt(mSelectedPosition);
                break;
            default:
                return super.onContextItemSelected(item);
        }
        return true;
    }

    private ItemTouchHelper.Callback mSwipeToRemoveCallback =
            new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.END | ItemTouchHelper.START) {
                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                    return false;
                }

                @Override
                public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                    final int position = viewHolder.getAdapterPosition();
                    removeItemAt(position);
                }
            };

    private void removeItemAt(final int position) {
        final SmsCodeRule itemToRemove = mRuleAdapter.getItem(position);
        if (itemToRemove == null) {
            return;
        }
        mRuleAdapter.removeItemAt(position);

        Snackbar snackbar = SnackbarHelper.makeLong(mRecyclerView, R.string.removed);
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
                if (event != DISMISS_EVENT_ACTION) {
                    try {
                        DBManager.get(mActivity).removeSmsCodeRule(itemToRemove);
                    } catch (Exception e) {
                        XLog.e("Remove " + itemToRemove.toString() + " failed", e);
                    }
                }
            }
        });
        snackbar.setAction(R.string.revoke, v -> mRuleAdapter.addRule(position, itemToRemove));
        snackbar.show();
    }

    private RecyclerView.AdapterDataObserver mDataObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            refreshEmptyView();
        }
    };

    private void refreshEmptyView() {
        if (mRuleAdapter.getItemCount() == 0) {
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mEmptyView.setVisibility(View.GONE);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRuleSaveOrUpdate(Event.OnRuleCreateOrUpdate event) {
        if (event.type == RuleEditFragment.EDIT_TYPE_CREATE) {
            mRuleAdapter.addRule(event.codeRule);
        } else if (event.type == RuleEditFragment.EDIT_TYPE_UPDATE) {
            mRuleAdapter.updateAt(mSelectedPosition, event.codeRule);
        }
    }

    private void importOrExportRuleList(@BackupType int type, Uri importUri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            importOrExportRuleListAboveQ(type, importUri);
        } else {
            // 考虑到在低版本的 Android 系统中，不少 Rom 将 DocumentUI 阉割掉了，无法使用 SAF
            // Android P 及以前，使用原有方式进行文件导入导出
            importOrExportRuleListBelowQ(type, importUri);
        }
    }

    private void importOrExportRuleListAboveQ(@BackupType int type, Uri importUrl) {
        if (type == TYPE_IMPORT) {
            // Android Q 及以后，使用 SAF (Storage Access Framework) 来导入导出文档文件
            Intent importIntent = BackupManager.getImportRuleListSAFIntent();
            try {
                startActivityForResult(importIntent, REQUEST_CODE_IMPORT_RULES);
            } catch (Exception e) {
                // 防止某些 Rom 将 DocumentUI 阉割掉
                SnackbarHelper.makeLong(mRecyclerView, R.string.documents_ui_not_found).show();
            }
        } else if (type == TYPE_EXPORT) {
            // Android Q 及以后，使用 SAF (Storage Access Framework) 来导入导出文档文件
            Intent exportIntent = BackupManager.getExportRuleListSAFIntent();
            try {
                startActivityForResult(exportIntent, REQUEST_CODE_EXPORT_RULES);
            } catch (Exception e) {
                // 防止某些 Rom 将 DocumentUI 阉割掉
                SnackbarHelper.makeLong(mRecyclerView, R.string.documents_ui_not_found).show();
            }
        } else if (type == TYPE_IMPORT_DIRECT){
            requestPermission(type, importUrl);
        }
    }

    private void importOrExportRuleListBelowQ(@BackupType int type, Uri importUri) {
        requestPermission(type, importUri);
    }

    private void requestPermission(final @BackupType int type, final Uri importUri) {
        String[] permissions;
        if (type == TYPE_EXPORT) {
            permissions = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
            };
        }
        AndPermission.with(this)
                .runtime()
                .permission(permissions)
                .onGranted(data -> {
                    if (type == TYPE_IMPORT) {
                        attemptImportRuleList();
                    } else if (type == TYPE_EXPORT) {
                        attemptExportRuleList();
                    } else if (type == TYPE_IMPORT_DIRECT) {
                        showImportDialogConfirm(importUri);
                    }
                })
                .onDenied(data -> {
                    SnackbarHelper.makeLong(mRecyclerView, R.string.external_storage_perm_denied_prompt).show();
                })
                .start();
    }

    private void attemptExportRuleList() {
        if (mRuleAdapter.getItemCount() == 0) {
            SnackbarHelper.makeLong(mRecyclerView, R.string.rule_list_empty_snack_prompt).show();
            return;
        }

        final String defaultFilename = BackupManager.getDefaultBackupFilename();
        String hint = getString(R.string.backup_file_name);
        String content = getString(R.string.backup_file_dir, BackupManager.getBackupDir().getAbsolutePath());
        final MaterialDialog exportFilenameDialog = new MaterialDialog.Builder(mActivity)
                .title(R.string.backup_file_name)
                .content(content)
                .input(hint, defaultFilename, (dialog, input) -> {
                    File file = new File(BackupManager.getBackupDir(), input.toString());
                    new ExportAsyncTaskBelowQ(mActivity, RuleListFragment.this, mRuleAdapter, file, getString(R.string.exporting)).execute();
                })
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                .negativeText(R.string.cancel)
                .build();

        final EditText editText = exportFilenameDialog.getInputEditText();
        if (editText != null) {
            exportFilenameDialog.setOnShowListener(dialog -> {
                int stop = defaultFilename.length() - BackupManager.getBackupFileExtension().length();
                editText.setSelection(0, stop);
            });
            final MDButton positiveBtn =
                    exportFilenameDialog.getActionButton(DialogAction.POSITIVE);
            editText.addTextChangedListener(new TextWatcherAdapter() {
                @Override
                public void afterTextChanged(Editable s) {
                    positiveBtn.setEnabled(Utils.isValidFilename(s.toString()));
                }
            });
        }
        exportFilenameDialog.show();
    }

    private void attemptImportRuleList() {
        final File[] files = BackupManager.getBackupFiles();

        if (files == null || files.length == 0) {
            SnackbarHelper.makeLong(mRecyclerView, R.string.no_backup_exists).show();
            return;
        }

        String[] filenames = new String[files.length];
        for (int i = 0; i < filenames.length; i++) {
            filenames[i] = files[i].getName();
        }

        final MaterialDialog importDialog = new MaterialDialog.Builder(mActivity)
                .title(R.string.choose_backup_file)
                .items(filenames)
                .itemsCallback((dialog, itemView, position, text) -> {
                    File file = files[position];
                    Uri uri = Uri.fromFile(file);
                    showImportDialogConfirm(uri);
                })
                .build();
        importDialog.show();
    }

    private void showImportDialogConfirm(final Uri uri) {
        new MaterialDialog.Builder(mActivity)
                .title(R.string.import_confirmation_title)
                .content(R.string.import_confirmation_message)
                .positiveText(R.string.yes)
                .onPositive((dialog, which) ->
                        new ImportAsyncTask(mActivity, RuleListFragment.this, uri, getString(R.string.importing), true).execute()
                ).negativeText(R.string.no)
                .onNegative((dialog, which) ->
                        new ImportAsyncTask(mActivity, RuleListFragment.this, uri, getString(R.string.importing), false).execute()
                ).show();
    }

    private static class ExportAsyncTaskBelowQ extends DialogAsyncTask<Void, Void, ExportResult> {

        private File mFile;
        private RuleAdapter mRuleAdapter;
        private WeakReference<RuleListFragment> mWeakFragment;

        ExportAsyncTaskBelowQ(Context context, RuleListFragment ruleListFragment, RuleAdapter ruleAdapter, File file, String progressMsg) {
            this(context, progressMsg, false);
            mRuleAdapter = ruleAdapter;
            mFile = file;
            mWeakFragment = new WeakReference<>(ruleListFragment);
        }

        private ExportAsyncTaskBelowQ(Context context, String progressMsg, boolean cancelable) {
            super(context, progressMsg, cancelable);
        }

        @Override
        protected ExportResult doInBackground(Void... voids) {
            return BackupManager.exportRuleList(mFile, mRuleAdapter.getRuleList());
        }

        @Override
        protected void onPostExecute(ExportResult exportResult) {
            super.onPostExecute(exportResult);
            if (mWeakFragment.get() != null) {
                mWeakFragment.get().onExportCompleted(exportResult, mFile);
            }
        }
    }

    private static class ExportAsyncTaskSinceQ extends DialogAsyncTask<Void, Void, ExportResult> {
        private WeakReference<RuleListFragment> mWeakFragment;
        private WeakReference<Context> mWeakContext;
        private Uri mUri;
        private List<SmsCodeRule> mRuleList;

        ExportAsyncTaskSinceQ(Context context, RuleListFragment ruleListFragment, List<SmsCodeRule> rules, Uri uri, String progressMsg) {
            super(context, progressMsg, false);
            mWeakFragment = new WeakReference<>(ruleListFragment);
            mWeakContext = new WeakReference<>(context);
            mRuleList = rules;
            mUri = uri;
        }

        @Override
        protected ExportResult doInBackground(Void... voids) {
            if (mWeakContext.get() != null) {
                return BackupManager.exportRuleList(mWeakContext.get(), mUri, mRuleList);
            } else {
                return ExportResult.FAILED;
            }
        }

        @Override
        protected void onPostExecute(ExportResult exportResult) {
            super.onPostExecute(exportResult);
            if (mWeakFragment.get() != null) {
                mWeakFragment.get().onExportCompletedSinceQ(exportResult == ExportResult.SUCCESS);
            }
        }
    }

    private static class ImportAsyncTask extends DialogAsyncTask<Void, Void, ImportResult> {

        private WeakReference<Context> mContextRef;
        private WeakReference<RuleListFragment> mWeakFragment;
        private Uri mUri;
        private boolean mRetain;

        ImportAsyncTask(Context context, RuleListFragment ruleListFragment, Uri uri, String progressMsg, boolean retain) {
            this(context, progressMsg, false);
            mContextRef = new WeakReference<>(context);
            mUri = uri;
            mRetain = retain;
            mWeakFragment = new WeakReference<>(ruleListFragment);
        }

        ImportAsyncTask(Context context, String progressMsg, boolean cancelable) {
            super(context, progressMsg, cancelable);
        }

        @Override
        protected ImportResult doInBackground(Void... voids) {
            Context context;
            if ((context = mContextRef.get()) != null) {
                return BackupManager.importRuleList(context, mUri, mRetain);
            } else {
                return null;
            }
        }

        @Override
        protected void onPostExecute(ImportResult importResult) {
            super.onPostExecute(importResult);
            if (mWeakFragment.get() != null) {
                mWeakFragment.get().onImportComplete(importResult);
            }
        }
    }

    private void onExportCompleted(ExportResult exportResult, final File file) {
        int msgId;
        if (exportResult == ExportResult.SUCCESS) {
            msgId = R.string.export_succeed;
        } else {
            // ExportResult.FAILED
            msgId = R.string.export_failed;
        }
        Snackbar snackbar = SnackbarHelper.makeLong(mRecyclerView, msgId);
        if (exportResult == ExportResult.SUCCESS) {
            snackbar.setAction(R.string.share, v -> {
                if (mActivity != null) {
                    BackupManager.shareBackupFile(mActivity, file);
                }
            });
        }
        snackbar.show();
    }

    private void onExportCompletedSinceQ(boolean success) {
        int msgId = success ? R.string.export_succeed : R.string.export_failed;
        SnackbarHelper.makeLong(mRecyclerView, msgId).show();
    }

    private void onImportComplete(ImportResult importResult) {
        @StringRes int msg;
        switch (importResult) {
            case SUCCESS:
                refreshData();
                msg = R.string.import_succeed;
                break;
            case VERSION_MISSED:
                msg = R.string.import_failed_version_missed;
                break;
            case VERSION_UNKNOWN:
                msg = R.string.import_failed_version_unknown;
                break;
            case BACKUP_INVALID:
                msg = R.string.import_failed_backup_invalid;
                break;
            case READ_FAILED:
            default:
                msg = R.string.import_failed_read_error;
                break;
        }
        SnackbarHelper.makeLong(mRecyclerView, msg).show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CODE_EXPORT_RULES) {
                new ExportAsyncTaskSinceQ(mActivity, this, mRuleAdapter.getRuleList(), data.getData(), getString(R.string.exporting)).execute();
            } else if (requestCode == REQUEST_CODE_IMPORT_RULES) {
                showImportDialogConfirm(data.getData());
            }
        }
    }
}
